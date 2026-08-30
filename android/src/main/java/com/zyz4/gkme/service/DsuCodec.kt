package com.zyz4.gkme.service

import com.zyz4.gkme.model.GamepadState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object DsuConstants {
    const val PORT_DISCOVERY = 26761
    const val PORT_DATA = 26760
    const val PROTOCOL_VERSION: UShort = 1001u

    const val MAGIC_SERVER = "DSUS"
    const val MAGIC_CLIENT = "DSUC"

    const val TYPE_VERSION_INFO = 0x100000
    const val TYPE_CONTROLLER_INFO = 0x100001
    const val TYPE_CONTROLLER_DATA = 0x100002
    const val TYPE_MOTOR_INFO = 0x110001
    const val TYPE_RUMBLE = 0x110002

    const val SLOT_STATE_DISCONNECTED: UByte = 0u
    const val SLOT_STATE_RESERVED: UByte = 1u
    const val SLOT_STATE_CONNECTED: UByte = 2u

    const val DEVICE_MODEL_NONE: UByte = 0u
    const val DEVICE_MODEL_PARTIAL_GYRO: UByte = 1u
    const val DEVICE_MODEL_FULL_GYRO: UByte = 2u

    const val CONNECTION_USB: UByte = 1u
    const val CONNECTION_BLUETOOTH: UByte = 2u

    const val BATTERY_NA: UByte = 0x00u
    const val BATTERY_DYING: UByte = 0x01u
    const val BATTERY_LOW: UByte = 0x02u
    const val BATTERY_MEDIUM: UByte = 0x03u
    const val BATTERY_HIGH: UByte = 0x04u
    const val BATTERY_FULL: UByte = 0x05u
    const val BATTERY_CHARGING: UByte = 0xEEu
    const val BATTERY_CHARGED: UByte = 0xEFu

    const val CONNECTED_FLAG = 1
    const val NOT_CONNECTED_FLAG = 0

    const val TOTAL_PACKET_SIZE = 100
    const val HEADER_SIZE = 16
    const val DATA_PAYLOAD_SIZE = 80

    fun batteryFromAndroid(level: Int, isCharging: Boolean): UByte {
        if (isCharging) return BATTERY_CHARGING
        return when {
            level >= 95 -> BATTERY_CHARGED
            level >= 80 -> BATTERY_FULL
            level >= 60 -> BATTERY_HIGH
            level >= 30 -> BATTERY_MEDIUM
            level >= 10 -> BATTERY_LOW
            else -> BATTERY_DYING
        }
    }

    fun isBroadcastPacket(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0].toInt() and 0xFF == 192 &&
                data[1].toInt() and 0xFF == 168
    }
}

class DsuCodec {
    fun encodeHeader(magic: String, clientId: Int, payloadWithType: ByteArray): ByteArray {
        val totalLen = DsuConstants.HEADER_SIZE + payloadWithType.size
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(magic.toByteArray(Charsets.US_ASCII))
        buf.putShort(DsuConstants.PROTOCOL_VERSION.toShort())
        buf.putShort((payloadWithType.size).toShort())
        buf.putInt(0)
        buf.putInt(clientId)
        buf.put(payloadWithType)

        val packet = buf.array()
        val crc32 = CRC32()
        crc32.update(packet, 0, 8)
        crc32.update(ByteArray(4))
        crc32.update(packet, 12, packet.size - 12)
        val crc = crc32.value.toInt()

        val resultBuf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        resultBuf.put(magic.toByteArray(Charsets.US_ASCII))
        resultBuf.putShort(DsuConstants.PROTOCOL_VERSION.toShort())
        resultBuf.putShort((payloadWithType.size).toShort())
        resultBuf.putInt(crc)
        resultBuf.putInt(clientId)
        resultBuf.put(payloadWithType)

        return resultBuf.array()
    }

    fun decodeHeader(buffer: ByteArray): DsuPacketHeader? {
        if (buffer.size < DsuConstants.HEADER_SIZE + 4) return null
        val bb = ByteBuffer.wrap(buffer, 0, DsuConstants.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        bb.get(magic)
        val magicStr = String(magic, Charsets.US_ASCII)
        if (magicStr != DsuConstants.MAGIC_SERVER && magicStr != DsuConstants.MAGIC_CLIENT) return null

        val version = bb.getShort().toInt() and 0xFFFF
        val payloadLen = bb.getShort().toInt() and 0xFFFF
        val crc = bb.getInt()
        val clientId = bb.getInt()

        val totalPacket = DsuConstants.HEADER_SIZE + payloadLen
        if (buffer.size < totalPacket) return null

        val crc32 = CRC32()
        crc32.update(buffer, 0, 8)
        crc32.update(ByteArray(4))
        if (totalPacket > 12) {
            crc32.update(buffer, 12, totalPacket - 12)
        }
        val computedCrc = crc32.value.toInt()

        val payload = buffer.copyOfRange(DsuConstants.HEADER_SIZE, buffer.size)
        val eventTypeBB = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        val eventType = eventTypeBB.getInt()

        return DsuPacketHeader(
            magic = magicStr,
            version = version,
            payloadLen = payloadLen,
            crc = crc,
            clientId = clientId,
            eventType = eventType,
            computedCrc = computedCrc,
            payload = payload
        )
    }

    fun encodeControllerInfo(
        clientId: Int,
        slot: Int,
        mac: ByteArray = ByteArray(6),
        battery: UByte = DsuConstants.BATTERY_NA
    ): ByteArray {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(DsuConstants.TYPE_CONTROLLER_INFO)
        payload.put(slot.toUByte().toByte())
        payload.put(DsuConstants.SLOT_STATE_CONNECTED.toByte())
        payload.put(DsuConstants.DEVICE_MODEL_FULL_GYRO.toByte())
        payload.put(DsuConstants.CONNECTION_BLUETOOTH.toByte())
        val macBytes = if (mac.size >= 6) mac.copyOf(6) else ByteArray(6)
        payload.put(macBytes)
        payload.put(battery.toByte())
        payload.put(DsuConstants.NOT_CONNECTED_FLAG.toByte())

        return encodeHeader(DsuConstants.MAGIC_SERVER, clientId, payload.array())
    }

    fun encodeControllerInfoDisconnected(
        clientId: Int,
        slot: Int
    ): ByteArray {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(DsuConstants.TYPE_CONTROLLER_INFO)
        payload.put(slot.toUByte().toByte())
        payload.put(DsuConstants.SLOT_STATE_DISCONNECTED.toByte())
        payload.put(DsuConstants.DEVICE_MODEL_NONE.toByte())
        payload.put(0)
        payload.put(ByteArray(6))
        payload.put(DsuConstants.BATTERY_NA.toByte())
        payload.put(DsuConstants.NOT_CONNECTED_FLAG.toByte())

        return encodeHeader(DsuConstants.MAGIC_SERVER, clientId, payload.array())
    }

    fun encodeGamepadData(
        clientId: Int,
        slot: Int,
        state: GamepadState,
        packetNum: Int,
        mac: ByteArray = ByteArray(6),
        touchTimestamp: Long = System.nanoTime() / 1000L
    ): ByteArray {
        val dataBuf = ByteBuffer.allocate(DsuConstants.DATA_PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        dataBuf.put(slot.toUByte().toByte())
        dataBuf.put(DsuConstants.SLOT_STATE_CONNECTED.toByte())
        dataBuf.put(DsuConstants.DEVICE_MODEL_FULL_GYRO.toByte())
        dataBuf.put(DsuConstants.CONNECTION_BLUETOOTH.toByte())
        val macBytes = if (mac.size >= 6) mac.copyOf(6) else ByteArray(6)
        dataBuf.put(macBytes)
        dataBuf.put(DsuConstants.batteryFromAndroid(state.batteryLevel, state.isCharging).toByte())

        dataBuf.put(DsuConstants.CONNECTED_FLAG.toByte())
        dataBuf.putInt(packetNum)

        val buttons = state.buttons.toInt()
        val b16 = ((if (buttons and GamepadState.DPAD_BIT_LEFT != 0) 0x80 else 0) or
                (if (buttons and GamepadState.DPAD_BIT_DOWN != 0) 0x40 else 0) or
                (if (buttons and GamepadState.DPAD_BIT_RIGHT != 0) 0x20 else 0) or
                (if (buttons and GamepadState.DPAD_BIT_UP != 0) 0x10 else 0) or
                (if (buttons and GamepadState.L3 != 0) 0x04 else 0) or
                (if (buttons and GamepadState.R3 != 0) 0x02 else 0)).toByte()
        dataBuf.put(b16)

        val b17 = ((if (buttons and GamepadState.Y != 0) 0x80 else 0) or
                (if (buttons and GamepadState.B != 0) 0x40 else 0) or
                (if (buttons and GamepadState.A != 0) 0x20 else 0) or
                (if (buttons and GamepadState.X != 0) 0x10 else 0) or
                (if (buttons and GamepadState.RB != 0) 0x08 else 0) or
                (if (buttons and GamepadState.LB != 0) 0x04 else 0) or
                (if (buttons and GamepadState.RT != 0) 0x02 else 0) or
                (if (buttons and GamepadState.LT != 0) 0x01 else 0)).toByte()
        dataBuf.put(b17)

        dataBuf.put((if (buttons and GamepadState.START != 0) 255 else 0).toByte())
        dataBuf.put((if (buttons and GamepadState.SELECT != 0) 255 else 0).toByte())

        dataBuf.put((state.leftStickX.toInt() shr 8).toByte())
        dataBuf.put(((-state.leftStickY.toInt()).coerceAtMost(32767) shr 8).toByte())
        dataBuf.put((state.rightStickX.toInt() shr 8).toByte())
        dataBuf.put(((-state.rightStickY.toInt()).coerceAtMost(32767) shr 8).toByte())

        val dpad = state.dpad
        val dpadLeft = if (dpad == GamepadState.DPAD_LEFT || dpad == GamepadState.DPAD_UP_LEFT || dpad == GamepadState.DPAD_DOWN_LEFT) 255 else 0
        val dpadDown = if (dpad == GamepadState.DPAD_DOWN || dpad == GamepadState.DPAD_DOWN_LEFT || dpad == GamepadState.DPAD_DOWN_RIGHT) 255 else 0
        val dpadRight = if (dpad == GamepadState.DPAD_RIGHT || dpad == GamepadState.DPAD_UP_RIGHT || dpad == GamepadState.DPAD_DOWN_RIGHT) 255 else 0
        val dpadUp = if (dpad == GamepadState.DPAD_UP || dpad == GamepadState.DPAD_UP_LEFT || dpad == GamepadState.DPAD_UP_RIGHT) 255 else 0
        dataBuf.put(dpadLeft.toByte())
        dataBuf.put(dpadDown.toByte())
        dataBuf.put(dpadRight.toByte())
        dataBuf.put(dpadUp.toByte())
        dataBuf.put((if (buttons and GamepadState.Y != 0) 255 else 0).toByte())
        dataBuf.put((if (buttons and GamepadState.B != 0) 255 else 0).toByte())
        dataBuf.put((if (buttons and GamepadState.A != 0) 255 else 0).toByte())
        dataBuf.put((if (buttons and GamepadState.X != 0) 255 else 0).toByte())
        dataBuf.put((if (buttons and GamepadState.RB != 0) 255 else 0).toByte())
        dataBuf.put((if (buttons and GamepadState.LB != 0) 255 else 0).toByte())
        dataBuf.put(state.rightTrigger.toByte())
        dataBuf.put(state.leftTrigger.toByte())

        val touches = state.touches
        for (i in 0 until 2) {
            val tp = touches.getOrNull(i)
            if (tp != null && tp.active) {
                dataBuf.put(1)
                dataBuf.put(tp.id.toByte())
                dataBuf.putShort(tp.x.coerceIn(0, 3838).toShort())
                dataBuf.putShort(tp.y.coerceIn(0, 1884).toShort())
            } else {
                dataBuf.put(0)
                dataBuf.put(0)
                dataBuf.putShort(0)
                dataBuf.putShort(0)
            }
        }

        dataBuf.putLong(touchTimestamp)

        val gyroX = state.gyroX * 180.0f / kotlin.math.PI.toFloat()
        val gyroY = -state.gyroY * 180.0f / kotlin.math.PI.toFloat()
        val gyroZ = -state.gyroZ * 180.0f / kotlin.math.PI.toFloat()
        val accelX = -state.accelX / 9.80665f
        val accelY = -state.accelY / 9.80665f
        val accelZ = -state.accelZ / 9.80665f

        dataBuf.putFloat(accelX)
        dataBuf.putFloat(accelY)
        dataBuf.putFloat(accelZ)
        dataBuf.putFloat(gyroX)
        dataBuf.putFloat(gyroY)
        dataBuf.putFloat(gyroZ)

        val payloadWithType = ByteBuffer.allocate(4 + DsuConstants.DATA_PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        payloadWithType.putInt(DsuConstants.TYPE_CONTROLLER_DATA)
        payloadWithType.put(dataBuf.array())
        return encodeHeader(DsuConstants.MAGIC_SERVER, clientId, payloadWithType.array())
    }

    fun parseRumbleRequest(packetHeader: DsuPacketHeader): RumbleRequest? {
        if (packetHeader.eventType != DsuConstants.TYPE_RUMBLE) return null
        val data = packetHeader.payload
        if (data.size < 14) return null
        val bb = ByteBuffer.wrap(data, 4, 10).order(ByteOrder.LITTLE_ENDIAN)
        val mac = ByteArray(6)
        bb.get(mac)
        bb.getShort()
        val smallMotor = bb.get().toInt() and 0xFF
        val largeMotor = bb.get().toInt() and 0xFF
        return RumbleRequest(largeMotor = largeMotor, smallMotor = smallMotor)
    }

    fun parseControllerDataRequest(packetHeader: DsuPacketHeader): ControllerDataRequest? {
        if (packetHeader.eventType != DsuConstants.TYPE_CONTROLLER_DATA) return null
        val data = packetHeader.payload
        if (data.size < 12) return null
        val bb = ByteBuffer.wrap(data, 4, 8).order(ByteOrder.LITTLE_ENDIAN)
        val bitmask = bb.get().toInt() and 0xFF
        val slot = bb.get().toInt() and 0xFF
        val mac = ByteArray(6)
        bb.get(mac)
        return ControllerDataRequest(bitmask, slot, mac)
    }

    fun parseControllerInfoRequest(packetHeader: DsuPacketHeader): ControllerInfoRequest? {
        if (packetHeader.eventType != DsuConstants.TYPE_CONTROLLER_INFO) return null
        val data = packetHeader.payload
        if (data.size < 8) return null
        val bb = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
        val portCount = bb.getInt()
        val slots = data.copyOfRange(8, 8 + portCount.coerceAtMost(4)).map { it.toInt() and 0xFF }
        return ControllerInfoRequest(portCount, slots)
    }
}

data class DsuPacketHeader(
    val magic: String,
    val version: Int,
    val payloadLen: Int,
    val crc: Int,
    val clientId: Int,
    val eventType: Int,
    val computedCrc: Int,
    val payload: ByteArray
) {
    val crcValid: Boolean get() = crc == computedCrc
}

data class ControllerInfoRequest(
    val portCount: Int,
    val slots: List<Int>
)

data class ControllerDataRequest(
    val bitmask: Int,
    val slot: Int,
    val mac: ByteArray
)

data class RumbleRequest(
    val slot: Int = 0,
    val motorId: Int = 0,
    val intensity: Int = 0,
    val largeMotor: Int = 0,
    val smallMotor: Int = 0
)
