package com.vpnproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class VpnProxyService : VpnService() {

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false

    private val sessions = ConcurrentHashMap<ConnectionKey, TcpSession>()
    private var serverAddress = ""
    private var serverPort = 1080
    private var username = ""
    private var password = ""

    private var bytesSent = 0L
    private var bytesReceived = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverAddress = intent?.getStringExtra(EXTRA_SERVER_ADDR) ?: return START_NOT_STICKY
        serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 1080) ?: 1080
        username = intent?.getStringExtra(EXTRA_USERNAME) ?: ""
        password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""

        startForeground(NOTIFICATION_ID, createNotification("正在连接..."))
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        running = true

        val builder = Builder()
        builder.setSession("VPN Proxy")
        builder.setMtu(1500)
        builder.addAddress(VPN_IP, 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        vpnThread = Thread {
            try {
                val input = FileInputStream(vpnInterface!!.fileDescriptor)
                val output = FileOutputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteArray(65536)

                while (running) {
                    val len = input.read(buffer)
                    if (len <= 0) break

                    val packet = buffer.copyOf(len)
                    processPacket(packet, output)
                }
            } catch (e: Exception) {
                if (running) e.printStackTrace()
            } finally {
                cleanupAll()
            }
        }.apply {
            name = "VpnReader"
            start()
        }

        testProxyConnection()
    }

    private fun testProxyConnection() {
        Thread {
            try {
                val testSocket = Socket()
                protect(testSocket)
                testSocket.connect(InetSocketAddress(serverAddress, serverPort), 5000)
                testSocket.close()
                mainHandler.post { connectionCallback?.invoke(true) }
            } catch (e: Exception) {
                mainHandler.post { connectionCallback?.invoke(false) }
            }
        }.apply { name = "ProxyTester" }.start()
    }

    private fun processPacket(packet: ByteArray, output: FileOutputStream) {
        val header = PacketParser.parseIPv4(packet) ?: return

        if (header.protocol != 6) return

        val key = ConnectionKey(
            srcIp = header.srcIp.hostAddress ?: return,
            srcPort = header.srcPort,
            dstIp = header.dstIp.hostAddress ?: return,
            dstPort = header.dstPort
        )

        val flags = header.flags
        val isSyn = (flags and PacketHeader.TCP_FLAG_SYN) != 0
        val isAck = (flags and PacketHeader.TCP_FLAG_ACK) != 0
        val isFin = (flags and PacketHeader.TCP_FLAG_FIN) != 0
        val isRst = (flags and PacketHeader.TCP_FLAG_RST) != 0
        val hasData = header.payload.isNotEmpty()

        when {
            isSyn && !isAck -> handleSyn(key, header, output)
            isRst -> handleRst(key)
            isFin -> handleFin(key, header, output)
            hasData && isAck -> handleData(key, header, output)
            else -> handleKeepAlive(key, header, output)
        }
    }

    private fun handleSyn(key: ConnectionKey, header: PacketHeader, output: FileOutputStream) {
        if (sessions.containsKey(key)) {
            sessions.remove(key)?.close()
        }

        val session = TcpSession(
            key = key,
            clientSeq = header.seqNum + 1,
            clientAck = 0L
        )

        try {
            val dstAddr = InetAddress.getByName(key.dstIp).address
            val forwarder = TcpForwarder(
                serverAddress = serverAddress,
                serverPort = serverPort,
                username = username,
                password = password,
                dstAddress = dstAddr,
                dstPort = key.dstPort,
                onDataReceived = { data -> onProxyData(key, data, output) },
                onDisconnected = { onProxyDisconnected(key, output) },
                socketProtector = { socket -> protect(socket) }
            )
            session.forwarder = forwarder
            sessions[key] = session
            forwarder.start()

            val synAck = PacketBuilder.buildTcpSynAck(
                srcIp = header.dstIp,
                dstIp = header.srcIp,
                srcPort = header.dstPort,
                dstPort = header.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientSeq
            )
            writePacket(output, synAck)
            session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            session.state = TcpSession.State.SYN_RECEIVED
        } catch (e: Exception) {
            e.printStackTrace()
            sessions.remove(key)
            val rst = PacketBuilder.buildTcpRst(
                srcIp = header.dstIp,
                dstIp = header.srcIp,
                srcPort = header.dstPort,
                dstPort = header.srcPort,
                seqNum = 0, ackNum = header.seqNum + 1
            )
            writePacket(output, rst)
        }
    }

    private fun handleData(key: ConnectionKey, header: PacketHeader, output: FileOutputStream) {
        val session = sessions[key] ?: return

        if (session.state == TcpSession.State.SYN_RECEIVED) {
            session.state = TcpSession.State.ESTABLISHED
            session.clientAck = header.ackNum
        }

        if (header.payload.isNotEmpty()) {
            session.clientSeq = (header.seqNum + header.payload.size) and 0xFFFFFFFFL
            session.clientAck = header.ackNum
            session.forwarder?.sendData(header.payload)
            bytesSent += header.payload.size

            val ack = PacketBuilder.buildTcpResponse(
                srcIp = InetAddress.getByName(key.dstIp),
                dstIp = InetAddress.getByName(key.srcIp),
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientSeq,
                flags = PacketHeader.TCP_FLAG_ACK,
                payload = ByteArray(0)
            )
            writePacket(output, ack)
        }
    }

    private fun handleFin(key: ConnectionKey, header: PacketHeader, output: FileOutputStream) {
        val session = sessions[key] ?: return
        session.clientSeq = (header.seqNum + 1) and 0xFFFFFFFFL
        session.forwarder?.close()
        session.state = TcpSession.State.CLOSING

        val finAck = PacketBuilder.buildTcpFinAck(
            srcIp = InetAddress.getByName(key.dstIp),
            dstIp = InetAddress.getByName(key.srcIp),
            srcPort = key.dstPort,
            dstPort = key.srcPort,
            seqNum = session.serverSeq,
            ackNum = session.clientSeq
        )
        writePacket(output, finAck)
        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL

        sessions.remove(key)
    }

    private fun handleRst(key: ConnectionKey) {
        sessions.remove(key)?.close()
    }

    private fun handleKeepAlive(key: ConnectionKey, header: PacketHeader, output: FileOutputStream) {
        val session = sessions[key] ?: return
        if (session.state == TcpSession.State.ESTABLISHED || session.state == TcpSession.State.SYN_RECEIVED) {
            val ack = PacketBuilder.buildTcpResponse(
                srcIp = InetAddress.getByName(key.dstIp),
                dstIp = InetAddress.getByName(key.srcIp),
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientSeq,
                flags = PacketHeader.TCP_FLAG_ACK,
                payload = ByteArray(0)
            )
            writePacket(output, ack)
        }
    }

    private fun onProxyData(key: ConnectionKey, data: ByteArray, output: FileOutputStream) {
        val session = sessions[key] ?: return
        try {
            val pkt = PacketBuilder.buildTcpResponse(
                srcIp = InetAddress.getByName(key.dstIp),
                dstIp = InetAddress.getByName(key.srcIp),
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientSeq,
                flags = PacketHeader.TCP_FLAG_PSH or PacketHeader.TCP_FLAG_ACK,
                payload = data
            )
            session.serverSeq = (session.serverSeq + data.size) and 0xFFFFFFFFL
            writePacket(output, pkt)
            bytesReceived += data.size
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onProxyDisconnected(key: ConnectionKey, output: FileOutputStream) {
        val session = sessions[key] ?: return
        val finAck = PacketBuilder.buildTcpFinAck(
            srcIp = InetAddress.getByName(key.dstIp),
            dstIp = InetAddress.getByName(key.srcIp),
            srcPort = key.dstPort,
            dstPort = key.srcPort,
            seqNum = session.serverSeq,
            ackNum = session.clientSeq
        )
        writePacket(output, finAck)
        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
        sessions.remove(key)
    }

    private fun writePacket(output: FileOutputStream, data: ByteArray) {
        try {
            output.write(data)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun cleanupAll() {
        running = false
        for ((_, session) in sessions) {
            session.close()
        }
        sessions.clear()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopVpn() {
        running = false
        vpnThread?.interrupt()
        vpnThread = null
        cleanupAll()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Proxy",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Proxy")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    data class ConnectionKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    class TcpSession(
        val key: ConnectionKey,
        var clientSeq: Long,
        var clientAck: Long = 0L,
        var serverSeq: Long = Random.nextLong() and 0xFFFFFFFFL,
        var forwarder: TcpForwarder? = null,
        var state: State = State.INIT
    ) {
        enum class State { INIT, SYN_RECEIVED, ESTABLISHED, CLOSING }

        fun close() {
            forwarder?.close()
            forwarder = null
            state = State.CLOSING
        }
    }

    companion object {
        var connectionCallback: ((Boolean) -> Unit)? = null

        const val EXTRA_SERVER_ADDR = "server_addr"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val VPN_IP = "10.0.0.1"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_proxy_channel"
    }
}
