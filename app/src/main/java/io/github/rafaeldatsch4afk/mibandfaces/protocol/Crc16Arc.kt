package io.github.rafaeldatsch4afk.mibandfaces.protocol

/**
 * CRC-16/ARC (polinômio 0x8005 refletido = 0xA001, valor inicial 0, sem XOR final).
 * É o checksum do payload nos quadros SPP V2 (XiaomiSppPacketV2.calculatePayloadChecksum no
 * Gadgetbridge).
 */
object Crc16Arc {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xffff
    }
}
