package io.github.rafaeldatsch4afk.mibandfaces.testutil

import com.google.protobuf.ByteString
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.DataUpload
import io.github.rafaeldatsch4afk.mibandfaces.proto.DataUploadAck
import io.github.rafaeldatsch4afk.mibandfaces.proto.Rpk
import io.github.rafaeldatsch4afk.mibandfaces.proto.RpkInfoList
import io.github.rafaeldatsch4afk.mibandfaces.proto.RpkInstallStart
import io.github.rafaeldatsch4afk.mibandfaces.proto.RpkList
import io.github.rafaeldatsch4afk.mibandfaces.proto.Watchface
import io.github.rafaeldatsch4afk.mibandfaces.proto.WatchfaceInfo
import io.github.rafaeldatsch4afk.mibandfaces.proto.WatchfaceList
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/**
 * Banda simulada com watch faces e mini-apps: lista, ativa, remove e recebe o envio em partes, conferindo o
 * MD5, o tamanho e o CRC32 do payload (formato de XiaomiDataUploadService).
 */
class FaceBand(authKey: ByteArray, transport: FakeBandTransport = FakeBandTransport()) : FakeBand(authKey, transport) {
    data class Face(val id: String, val name: String, var active: Boolean, val canDelete: Boolean)

    val faces = CopyOnWriteArrayList(
        listOf(
            Face("100", "Sistema", active = true, canDelete = false),
            Face("200", "Esportiva", active = false, canDelete = true),
        ),
    )

    /** chunkSize anunciado na resposta 22/0 (null = ausente, como na Mi Band 8). */
    var chunkSize: Int? = 1024
    var uploadErrno = 0

    /** Se true, a banda guarda o que já recebeu e pede para retomar daí (resumePosition). */
    var resumable = false

    /** resumePosition mandado na última resposta 22/0. */
    @Volatile
    var lastResumePosition = 0
        private set

    /** Bytes do arquivo recebidos em conexões anteriores (retomada). */
    private var filePrefix = ByteArray(0)

    /** Chamado quando a banda recebe a parte [index] (1-based). */
    var onPart: (index: Int, total: Int) -> Unit = { _, _ -> }

    val partSizes = CopyOnWriteArrayList<Int>()
    var uploadOk: Boolean? = null
        private set

    /** Mini-apps instalados (id, nome) e o sha que a banda devolve na lista. */
    val apps = CopyOnWriteArrayList<Pair<String, String>>()

    /** Status da resposta 20/1 (0 = aceito). */
    var rpkInstallStatus = 0

    /** false = a banda ignora os comandos de mini-app, como a Mi Band 8 do miband-hack. */
    @Volatile
    var answersApps = true

    /** false = responde à lista de apps, mas ignora o pedido de instalação. */
    @Volatile
    var answersAppInstall = true

    /** Tipo do último envio 22/0 (16 = face, 64 = mini-app). */
    @Volatile
    var uploadType = 0
        private set

    private var pendingAppId: String? = null
    private var pendingId: String? = null
    private var pendingSize = 0
    private val upload = ByteArrayOutputStream()

    override fun handleCommand(command: Command) {
        if (command.type == 20 && (!answersApps || (command.subtype == 1 && !answersAppInstall))) return
        when {
            command.type == 4 && command.subtype == 0 -> sendList()
            command.type == 4 && command.subtype == 1 -> {
                val id = command.watchface.watchfaceId
                if (faces.any { it.id == id }) faces.forEach { it.active = it.id == id }
                reply(Command.newBuilder().setType(4).setSubtype(1).setWatchface(Watchface.newBuilder().setAck(1)).build())
            }
            command.type == 4 && command.subtype == 2 -> {
                val id = command.watchface.watchfaceId
                faces.removeIf { it.id == id && it.canDelete && !it.active }
                reply(Command.newBuilder().setType(4).setSubtype(2).setWatchface(Watchface.newBuilder().setAck(1)).build())
            }
            command.type == 4 && command.subtype == 4 -> {
                val start = command.watchface.watchfaceInstallStart
                val status = if (faces.any { it.id == start.id }) 2 else 0
                if (status == 0) {
                    if (start.id != pendingId) filePrefix = ByteArray(0)
                    pendingId = start.id
                    pendingSize = start.size
                }
                reply(Command.newBuilder().setType(4).setSubtype(4).setWatchface(Watchface.newBuilder().setInstallStatus(status)).build())
            }
            command.type == 20 && command.subtype == 0 -> {
                val list = RpkList.newBuilder()
                for ((id, name) in apps) {
                    list.addRpkInfo(RpkInfoList.newBuilder().setId(id).setName(name).setSha(ByteString.copyFromUtf8("sha-$id")))
                }
                reply(Command.newBuilder().setType(20).setSubtype(0).setRpk(Rpk.newBuilder().setRpkList(list)).build())
            }
            command.type == 20 && command.subtype == 1 -> {
                val info = command.rpk.rpkInfo
                if (rpkInstallStatus == 0) {
                    if (info.id != pendingAppId) filePrefix = ByteArray(0)
                    pendingAppId = info.id
                    pendingSize = info.size
                }
                reply(Command.newBuilder().setType(20).setSubtype(1).setRpk(Rpk.newBuilder().setRpkInstallStart(RpkInstallStart.newBuilder().setCmd(rpkInstallStatus))).build())
            }
            command.type == 20 && command.subtype == 3 -> {
                val del = command.rpk.rpkDel
                check(del.sha == ByteString.copyFromUtf8("sha-${del.id}")) { "sha errado ao remover" }
                apps.removeIf { it.first == del.id }
                // Como o Gadgetbridge descreve: a banda não responde à remoção.
            }
            command.type == 22 && command.subtype == 0 -> {
                val request = command.dataUpload.dataUploadRequest
                check(request.type == 16 || request.type == 64) { "tipo de upload ${request.type}" }
                uploadType = request.type
                check(request.size == pendingSize) { "tamanho diferente do pedido de instalação" }
                if (resumable) {
                    // Guarda os bytes do arquivo que chegaram antes da queda (o payload começa com 22 bytes de cabeçalho).
                    val previous = upload.toByteArray()
                    if (previous.size > HEADER) {
                        val got = previous.copyOfRange(HEADER, minOf(previous.size, HEADER + pendingSize - filePrefix.size))
                        filePrefix += got
                    }
                } else {
                    filePrefix = ByteArray(0)
                }
                upload.reset()
                uploadOk = null
                lastResumePosition = filePrefix.size
                val ack = DataUploadAck.newBuilder().setErrno(uploadErrno).setResumePosition(filePrefix.size)
                chunkSize?.let { ack.setChunkSize(it) }
                reply(Command.newBuilder().setType(22).setSubtype(0).setDataUpload(DataUpload.newBuilder().setDataUploadAck(ack)).build())
            }
            else -> super.handleCommand(command)
        }
    }

    override fun onDataChannel(body: ByteArray) {
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val total = b.short.toInt() and 0xffff
        val index = b.short.toInt() and 0xffff
        partSizes += body.size
        upload.write(body, 4, body.size - 4)
        onPart(index, total)
        if (index == total) finishUpload()
    }

    private fun finishUpload() {
        val payload = upload.toByteArray()
        val p = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val ok = runCatching {
            check(p.get().toInt() == 0)
            check(p.get().toInt() and 0xff == uploadType)
            val md5 = ByteArray(16).also { p.get(it) }
            val size = p.int
            check(size == pendingSize)
            val tail = ByteArray(size - filePrefix.size).also { p.get(it) }
            val file = filePrefix + tail
            val crc = p.int.toLong() and 0xffffffffL
            check(!p.hasRemaining())
            check(MessageDigest.getInstance("MD5").digest(file).contentEquals(md5))
            check(CRC32().apply { update(payload, 0, payload.size - 4) }.value == crc)
        }.isSuccess
        uploadOk = ok
        if (ok && uploadType == 16) faces += Face(pendingId!!, "Nova face", active = false, canDelete = true)
        if (ok && uploadType == 64) {
            apps += pendingAppId!! to "Novo app"
            // Aviso de instalação concluída (20/2), como a banda manda.
            reply(Command.newBuilder().setType(20).setSubtype(2).build())
        }
    }

    private companion object {
        /** 00 | tipo | md5 (16) | tamanho (4) */
        const val HEADER = 22
    }

    private fun sendList() {
        val list = WatchfaceList.newBuilder()
        for (f in faces) {
            list.addWatchface(WatchfaceInfo.newBuilder().setId(f.id).setName(f.name).setActive(f.active).setCanDelete(f.canDelete))
        }
        reply(Command.newBuilder().setType(4).setSubtype(0).setWatchface(Watchface.newBuilder().setWatchfaceList(list)).build())
    }
}
