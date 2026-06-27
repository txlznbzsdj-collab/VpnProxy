package com.vpnproxy.app

import java.net.InetAddress
import java.nio.ByteBuffer

data class PacketHeader(
    val srcIp: InetAddress,
    val dstIp: InetAddress,
    val protocol: Int,
    val srcPort: Int,
    val dstPort: Int,
    val seqNum: Long,
    val ackNum: Long,
    val flags: Int,
    val headerLength: Int,
    val payload: ByteArray,
    val totalLength: Int
) {
    companion object {
        const val TCP_FLAG_FIN = 0x01
        const val TCP_FLAG_SYN = 0x02
        const val TCP_FLAG_RST = 0x04
        const val TCP_FLAG_PSH = 0x08
        const val TCP_FLAG_ACK = 0x10
    }
}

object PacketParser {

    fun parseIPv4(packet: ByteArray): PacketHeader? {
        if (packet.size < 20) return null

        val versionAndIhl = packet[0].toInt() and 0xFF
        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || packet.size < ihl + 20) return null

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (packet.size < totalLength) return null

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = InetAddress.getByAddress(packet.sliceArray(12..15))
        val dstIp = InetAddress.getByAddress(packet.sliceArray(16..19))

        if (protocol != 6) {
            return PacketHeader(
                srcIp = srcIp, dstIp = dstIp, protocol = protocol,
                srcPort = 0, dstPort = 0,
                seqNum = 0, ackNum = 0, flags = 0,
                headerLength = ihl, payload = ByteArray(0),
                totalLength = totalLength
            )
        }

        val tcpOffset = ihl
        if (packet.size < tcpOffset + 20) return null

        val srcPort = ((packet[tcpOffset].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[tcpOffset + 2].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 3].toInt() and 0xFF)
        val seqNum = ((packet[tcpOffset + 4].toLong() and 0xFF) shl 24) or
                ((packet[tcpOffset + 5].toLong() and 0xFF) shl 16) or
                ((packet[tcpOffset + 6].toLong() and 0xFF) shl 8) or
                (packet[tcpOffset + 7].toLong() and 0xFF)
        val ackNum = ((packet[tcpOffset + 8].toLong() and 0xFF) shl 24) or
                ((packet[tcpOffset + 9].toLong() and 0xFF) shl 16) or
                ((packet[tcpOffset + 10].toLong() and 0xFF) shl 8) or
                (packet[tcpOffset + 11].toLong() and 0xFF)
        val dataOffsetAndFlags = ((packet[tcpOffset + 12].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 13].toInt() and 0xFF)
        val tcpHeaderLen = ((dataOffsetAndFlags shr 12) and 0x0F) * 4
        val flags = dataOffsetAndFlags and 0x3F

        val payloadStart = tcpOffset + tcpHeaderLen
        val payloadSize = totalLength - payloadStart
        val payload = if (payloadSize > 0) packet.sliceArray(payloadStart until totalLength) else ByteArray(0)

        return PacketHeader(
            srcIp = srcIp, dstIp = dstIp, protocol = protocol,
            srcPort = srcPort, dstPort = dstPort,
            seqNum = seqNum, ackNum = ackNum,
            flags = flags, headerLength = tcpHeaderLen,
            payload = payload, totalLength = totalLength
        )
    }
}

object PacketBuilder {

    fun buildTcpResponse(
        srcIp: InetAddress, dstIp: InetAddress,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int, payload: ByteArray,
        tcpHeaderLen: Int = 20
    ): ByteArray {
        val totalLen = 20 + tcpHeaderLen + payload.size
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putInt(0)
        buf.put(0x40.toByte())
        buf.put(6.toByte())
        buf.putShort(0)
        buf.put(srcIp.address)
        buf.put(dstIp.address)

        val ipHeader = buf.array()
        val ipChecksum = calculateChecksum(ipHeader, 0, 20)
        ipHeader[10] = (ipChecksum shr 8).toByte()
        ipHeader[11] = (ipChecksum and 0xFF).toByte()

        val tcpOffset = 20
        buf.position(tcpOffset)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seqNum.toInt())
        buf.putInt(ackNum.toInt())
        val dataOffset = tcpHeaderLen / 4
        buf.putShort(((dataOffset shl 12) or (flags and 0xFF)).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0)
        buf.putShort(0)

        buf.position(tcpOffset + tcpHeaderLen)
        buf.put(payload)

        val tcpSegment = buf.array().sliceArray(tcpOffset until totalLen)
        val pseudoHeader = ByteBuffer.allocate(12 + tcpSegment.size)
        pseudoHeader.put(srcIp.address)
        pseudoHeader.put(dstIp.address)
        pseudoHeader.put(0.toByte())
        pseudoHeader.put(6.toByte())
        pseudoHeader.putShort(tcpSegment.size.toShort())
        pseudoHeader.put(tcpSegment)
        val tcpChecksum = calculateChecksum(pseudoHeader.array(), 0, pseudoHeader.array().size)
        buf.position(tcpOffset + 16)
        buf.putShort(tcpChecksum.toShort())

        return buf.array()
    }

    fun buildTcpRst(
        srcIp: InetAddress, dstIp: InetAddress,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long
    ): ByteArray {
        return buildTcpResponse(
            srcIp, dstIp, srcPort, dstPort,
            seqNum, ackNum,
            PacketHeader.TCP_FLAG_RST or PacketHeader.TCP_FLAG_ACK,
            ByteArray(0), 20
        )
    }

    fun buildTcpSynAck(
        srcIp: InetAddress, dstIp: InetAddress,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long
    ): ByteArray {
        return buildTcpResponse(
            srcIp, dstIp, srcPort, dstPort,
            seqNum, ackNum,
            PacketHeader.TCP_FLAG_SYN or PacketHeader.TCP_FLAG_ACK,
            ByteArray(0), 20
        )
    }

    fun buildTcpFinAck(
        srcIp: InetAddress, dstIp: InetAddress,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long
    ): ByteArray {
        return buildTcpResponse(
            srcIp, dstIp, srcPort, dstPort,
            seqNum, ackNum,
            PacketHeader.TCP_FLAG_FIN or PacketHeader.TCP_FLAG_ACK,
            ByteArray(0), 20
        )
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
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
        return (sum.inv() and 0xFFFF)
    }
}
