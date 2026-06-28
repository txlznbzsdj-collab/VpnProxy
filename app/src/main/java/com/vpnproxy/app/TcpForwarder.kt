package com.vpnproxy.app

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TcpForwarder(
    private val serverAddress: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val dstAddress: ByteArray,
    private val dstPort: Int,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
    private val socketProtector: ((Socket) -> Unit)? = null
) : Thread("TcpForwarder") {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val running = AtomicBoolean(true)

    private val handshakeLock = Any()
    private var handshakeDone = false
    private var httpDirectMode = false

    override fun run() {
        try {
            httpDirectMode = dstPort == 80 || dstPort == 8080 || dstPort == 8000

            Log.d(TAG, "连接代理服务器: $serverAddress:$serverPort -> 目标:${InetAddress.getByAddress(dstAddress).hostAddress}:$dstPort")
            socket = Socket()
            socketProtector?.invoke(socket!!)
            socket!!.connect(InetSocketAddress(serverAddress, serverPort), 10000)
            socket!!.soTimeout = 0
            outputStream = socket!!.getOutputStream()
            inputStream = socket!!.getInputStream()
            Log.d(TAG, "代理连接成功, 等待握手完成")

            synchronized(handshakeLock) {
                while (!handshakeDone && running.get()) {
                    (handshakeLock as java.lang.Object).wait(5000)
                }
            }
            if (!handshakeDone) {
                throw Exception("Handshake timeout")
            }
            Log.d(TAG, "握手完成, 开始数据转发")

            val buffer = ByteArray(65536)
            while (running.get() && !isInterrupted) {
                val len = inputStream!!.read(buffer)
                if (len <= 0) break
                onDataReceived(buffer.copyOf(len))
            }
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "代理连接被拒绝: $serverAddress:$serverPort", e)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "代理连接超时: $serverAddress:$serverPort", e)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "代理连接IO异常: $serverAddress:$serverPort", e)
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "代理转发异常", e)
        } finally {
            running.set(false)
            close()
            onDisconnected()
        }
    }

    fun sendData(data: ByteArray) {
        try {
            if (!handshakeDone) {
                synchronized(handshakeLock) {
                    if (!handshakeDone) {
                        if (httpDirectMode) {
                            Log.d(TAG, "HTTP直连模式, 重写请求")
                            val rewritten = rewriteHttpRequest(data)
                            outputStream?.write(rewritten)
                            outputStream?.flush()
                        } else {
                            val sni = extractSni(data)
                            val host = sni ?: InetAddress.getByAddress(dstAddress).hostAddress
                            Log.d(TAG, "发送CONNECT握手: $host:$dstPort")
                            doConnectHandshake(host)
                            outputStream?.write(data)
                            outputStream?.flush()
                        }
                        handshakeDone = true
                        (handshakeLock as java.lang.Object).notify()
                        return
                    }
                }
            }
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "sendData异常", e)
            close()
        }
    }

    private fun doConnectHandshake(host: String) {
        val os = outputStream!!
        val ins = inputStream!!
        val connectReq = "CONNECT $host:$dstPort HTTP/1.1\r\nHost: $host:$dstPort\r\n\r\n"
        os.write(connectReq.toByteArray())
        os.flush()
        val responseLine = readLine(ins) ?: throw Exception("HTTP代理无响应")
        if (!responseLine.contains("200")) {
            val sb = StringBuilder("HTTP CONNECT失败: $responseLine")
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                sb.append(" | ").append(line)
            }
            Log.w(TAG, sb.toString())
            throw Exception("HTTP代理拒绝连接: $responseLine")
        }
        while (true) {
            val line = readLine(ins) ?: break
            if (line.isEmpty()) break
        }
    }

    private fun rewriteHttpRequest(data: ByteArray): ByteArray {
        val text = data.toString(Charsets.ISO_8859_1)

        val firstLineEnd = text.indexOf("\r\n")
        if (firstLineEnd < 0) return data

        val requestLine = text.substring(0, firstLineEnd)
        val parts = requestLine.split(" ")
        if (parts.size < 2) return data

        val method = parts[0]
        val path = parts[1]

        if (path.startsWith("http://") || path.startsWith("https://")) return data

        val hostSearch = text.indexOf("\r\nhost:", 0, ignoreCase = true)
        if (hostSearch < 0) return data

        val hostValueStart = hostSearch + 2 + 5
        val hostLineEnd = text.indexOf("\r\n", hostValueStart)
        if (hostLineEnd < 0) return data

        val host = text.substring(hostValueStart, hostLineEnd).trim()

        val absoluteUrl = "http://$host$path"
        val rewrittenLine = "$method $absoluteUrl HTTP/1.1"

        val out = ByteArray(data.size + absoluteUrl.length - path.length)
        val line1 = rewrittenLine.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(line1, 0, out, 0, line1.size)
        System.arraycopy(data, firstLineEnd, out, line1.size, data.size - firstLineEnd)

        return out
    }

    private fun extractSni(data: ByteArray): String? {
        if (data.size < 5) return null
        if ((data[0].toInt() and 0xFF) != 0x16) return null

        val recordLen = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (data.size < 5 + recordLen) return null

        var offset = 5
        if (offset >= data.size) return null
        if ((data[offset].toInt() and 0xFF) != 0x01) return null
        offset += 1

        offset += 3
        offset += 2
        offset += 32

        if (offset >= data.size) return null
        val sessionIdLen = data[offset].toInt() and 0xFF
        offset += 1 + sessionIdLen

        if (offset + 1 >= data.size) return null
        val cipherLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2 + cipherLen

        if (offset >= data.size) return null
        val compLen = data[offset].toInt() and 0xFF
        offset += 1 + compLen

        if (offset + 1 >= data.size) return null
        val extLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val extEnd = offset + extLen

        while (offset + 3 < extEnd && offset + 3 < data.size) {
            val extType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val extDataLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (offset + extDataLen > data.size) return null

            if (extType == 0x0000 && offset + 3 < data.size) {
                val nameType = data[offset + 2].toInt() and 0xFF
                if (nameType == 0x00) {
                    val nameLen = ((data[offset + 3].toInt() and 0xFF) shl 8) or (data[offset + 4].toInt() and 0xFF)
                    if (offset + 5 + nameLen <= data.size) {
                        return data.sliceArray(offset + 5 until offset + 5 + nameLen).toString(Charsets.US_ASCII)
                    }
                }
            }
            offset += extDataLen
        }
        return null
    }

    fun close() {
        running.set(false)
        interrupt()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    val isRunning: Boolean get() = running.get()

    companion object {
        const val TAG = "TcpForwarder"
        private fun readLine(ins: InputStream): String? {
            val buf = StringBuilder()
            var prev = 0
            while (true) {
                val b = ins.read()
                if (b < 0) return null
                if (prev == 0x0D && b == 0x0A) {
                    buf.setLength(buf.length - 1)
                    return buf.toString()
                }
                buf.append(b.toChar())
                prev = b
            }
        }
    }
}
