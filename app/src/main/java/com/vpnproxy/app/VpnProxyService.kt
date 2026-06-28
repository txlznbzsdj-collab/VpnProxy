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
import android.util.Log
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
        Log.w(TAG, "VPN被系统撤销")
        stopVpn()
    }

    override fun onDestroy() {
        Log.d(TAG, "服务销毁")
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        Log.d(TAG, "启动VPN: server=$serverAddress:$serverPort")
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
            Log.e(TAG, "TUN接口建立失败")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            mainHandler.post { connectionCallback?.invoke(false, "VPN接口建立失败，请检查VPN权限") }
            return
        }
        Log.d(TAG, "TUN接口建立成功")

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
                if (running) Log.e(TAG, "VPN读取循环异常", e)
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
                Log.d(TAG, "测试代理连接: $serverAddress:$serverPort")
                val testSocket = Socket()
                protect(testSocket)
                testSocket.connect(InetSocketAddress(serverAddress, serverPort), 5000)
                testSocket.soTimeout = 5000

                val connMsg = "CONNECT 8.8.8.8:53 HTTP/1.1\r\nHost: 8.8.8.8:53\r\n\r\n"
                testSocket.getOutputStream().write(connMsg.toByteArray())

                val response = ByteArray(200)
                val len = testSocket.getInputStream().read(response)
                val respStr = String(response, 0, len)
                val ok = respStr.contains("200")

                if (ok) {
                    Log.d(TAG, "代理连接测试成功")
                } else {
                    Log.w(TAG, "代理响应异常: $respStr")
                }

                testSocket.close()
                val errorMsg = if (ok) null else "代理服务器返回异常响应: ${respStr.take(100)}"
                mainHandler.post { connectionCallback?.invoke(ok, errorMsg) }
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "DNS解析失败: $serverAddress", e)
                mainHandler.post { connectionCallback?.invoke(false, "DNS解析失败，无法解析服务器地址: $serverAddress") }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "连接被拒绝: $serverAddress:$serverPort", e)
                mainHandler.post { connectionCallback?.invoke(false, "连接被拒绝，服务器 $serverAddress:$serverPort 不可达") }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "连接超时: $serverAddress:$serverPort", e)
                mainHandler.post { connectionCallback?.invoke(false, "连接超时，服务器 $serverAddress:$serverPort 未响应") }
            } catch (e: java.io.IOException) {
                Log.e(TAG, "代理连接IO异常", e)
                mainHandler.post { connectionCallback?.invoke(false, "网络IO错误: ${e.localizedMessage ?: "未知错误"}") }
            } catch (e: Exception) {
                Log.e(TAG, "代理连接未知异常", e)
                mainHandler.post { connectionCallback?.invoke(false, "未知错误: ${e.localizedMessage ?: "请检查网络连接"}") }
            }
        }.apply { name = "ProxyTester" }.start()
    }

    private fun handleDnsQuery(packet: ByteArray, header: PacketHeader, output: FileOutputStream) {
        Thread {
            try {
                val ihl = header.headerLength
                val udpOffset = ihl
                if (packet.size < udpOffset + 8) return@Thread

                val srcPort = header.srcPort
                val dstPort = header.dstPort
                val dnsData = packet.sliceArray(udpOffset + 8 until header.totalLength)

                Log.d(TAG, "DNS查询 ${header.srcIp.hostAddress}:$srcPort -> $dstPort ${dnsData.size}bytes")

                val dnsSocket = Socket()
                protect(dnsSocket)
                dnsSocket.connect(InetSocketAddress("8.8.8.8", 53), 5000)
                dnsSocket.soTimeout = 5000

                val dnsOut = dnsSocket.getOutputStream()
                val dnsIn = dnsSocket.getInputStream()

                val lenPrefix = byteArrayOf(((dnsData.size shr 8) and 0xFF).toByte(), (dnsData.size and 0xFF).toByte())
                dnsOut.write(lenPrefix)
                dnsOut.write(dnsData)
                dnsOut.flush()

                val lenHi = dnsIn.read()
                val lenLo = dnsIn.read()
                if (lenHi < 0 || lenLo < 0) { dnsSocket.close(); return@Thread }
                val respLen = (lenHi shl 8) or lenLo
                val respData = ByteArray(respLen)
                var off = 0
                while (off < respLen) {
                    val r = dnsIn.read(respData, off, respLen - off)
                    if (r < 0) break
                    off += r
                }
                dnsSocket.close()
                if (off < respLen) return@Thread

                val udpRespLen = 8 + respData.size
                val totalLen = ihl + udpRespLen
                val buf = java.nio.ByteBuffer.allocate(totalLen)

                buf.put(0x45.toByte())
                buf.put(0x00.toByte())
                buf.putShort(totalLen.toShort())
                buf.putInt(0)
                buf.put(0x40.toByte())
                buf.put(17.toByte())
                buf.putShort(0)
                buf.put(header.dstIp.address)
                buf.put(header.srcIp.address)

                val ipChecksum = ipChecksum(buf.array(), 0, 20)
                buf.putShort(10, ipChecksum)

                buf.putShort(dstPort.toShort())
                buf.putShort(srcPort.toShort())
                buf.putShort(udpRespLen.toShort())
                buf.putShort(0)
                buf.put(respData)

                writePacket(output, buf.array())
                Log.d(TAG, "DNS响应 ${header.dstIp.hostAddress}:$dstPort -> ${header.srcIp.hostAddress}:$srcPort ${respData.size}bytes")
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "DNS转发超时", e)
            } catch (e: Exception) {
                Log.e(TAG, "DNS转发异常", e)
            }
        }.apply { name = "DnsForwarder" }.start()
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum > 0xFFFF) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return ((sum.inv() and 0xFFFF).toShort())
    }

    private fun processPacket(packet: ByteArray, output: FileOutputStream) {
        val header = PacketParser.parseIPv4(packet) ?: return

        if (header.protocol == 17 && header.dstPort == 53) {
            handleDnsQuery(packet, header, output)
            return
        }

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
            Log.e(TAG, "handleSyn异常 key=$key", e)
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
            Log.e(TAG, "onProxyData异常 key=$key", e)
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
        Log.d(TAG, "清理所有会话: ${sessions.size} 个活跃连接")
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
        Log.d(TAG, "停止VPN服务")
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
        var connectionCallback: ((Boolean, String?) -> Unit)? = null

        const val TAG = "VpnProxyService"
        const val EXTRA_SERVER_ADDR = "server_addr"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val VPN_IP = "10.0.0.1"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_proxy_channel"
    }
}
