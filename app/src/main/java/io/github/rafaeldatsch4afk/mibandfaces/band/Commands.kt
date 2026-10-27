package io.github.rafaeldatsch4afk.mibandfaces.band

import io.github.rafaeldatsch4afk.mibandfaces.proto.Command

/** Tipos e subtipos dos comandos protobuf (constantes dos serviços do Gadgetbridge). */
object Commands {
    // XiaomiSystemService
    const val SYSTEM = 2
    const val SYSTEM_BATTERY = 1
    const val SYSTEM_DEVICE_INFO = 2
    const val SYSTEM_CLOCK = 3

    // XiaomiWatchfaceService
    const val WATCHFACE = 4
    const val WATCHFACE_LIST = 0
    const val WATCHFACE_SET = 1
    const val WATCHFACE_DELETE = 2
    const val WATCHFACE_INSTALL = 4

    // XiaomiRpkService (mini-apps .rpk)
    const val RPK = 20
    const val RPK_LIST = 0
    const val RPK_INSTALL = 1
    const val RPK_INSTALLED = 2
    const val RPK_DELETE = 3

    // XiaomiDataUploadService
    const val DATA_UPLOAD = 22
    const val DATA_UPLOAD_START = 0
    const val UPLOAD_TYPE_WATCHFACE = 16
    const val UPLOAD_TYPE_RPK = 64

    fun simple(type: Int, subtype: Int): Command = Command.newBuilder().setType(type).setSubtype(subtype).build()

    fun Command.isA(type: Int, subtype: Int) = this.type == type && this.subtype == subtype
}
