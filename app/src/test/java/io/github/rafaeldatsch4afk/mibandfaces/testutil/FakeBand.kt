package io.github.rafaeldatsch4afk.mibandfaces.testutil

import com.google.protobuf.ByteString
import io.github.rafaeldatsch4afk.mibandfaces.proto.Account
import io.github.rafaeldatsch4afk.mibandfaces.proto.Auth
import io.github.rafaeldatsch4afk.mibandfaces.proto.Battery
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.CompanionDevice
import io.github.rafaeldatsch4afk.mibandfaces.proto.DeviceInfo
import io.github.rafaeldatsch4afk.mibandfaces.proto.Power
import io.github.rafaeldatsch4afk.mibandfaces.proto.System
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV1
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SppV2
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Banda simulada no nível dos comandos protobuf. A criptografia do lado da banda é feita aqui de
 * forma independente do código do app (HMAC do javax, CCM do BouncyCastle), para que um erro
 * no app não "se cancele" com o mesmo erro na banda.
 */
open class FakeBand(
    private val authKey: ByteArray,
    transport: FakeBandTransport = FakeBandTransport(),
) {
    /** Transporte da conexão atual (troca a cada [newTransport]). */
    @Volatile
    var transport: FakeBandTransport = transport
        private set

    /** Quantas conexões o celular abriu (via [newTransport]). */
    @Volatile
    var connections = 0
        private set

    /** Se true, a banda responde NO_BOUND (código 4) ao passo 1, como na captura real. */
    var refuseNoBound = false

    var companionReceived: CompanionDevice? = null
        private set

    var authenticated = false
        private set

    val commandsReceived: MutableList<Command> = java.util.Collections.synchronizedList(mutableListOf())

    private var phoneNonce = ByteArray(0)
    private var bandNonce = ByteArray(0)

    /** Do ponto de vista do celular: hostDec decifra o que vem da banda, hostEnc cifra o que vai. */
    private var hostDecKey = ByteArray(0)
    private var hostEncKey = ByteArray(0)
    private var hostEncNonce = ByteArray(0)

    init {
        wire(transport)
    }

    private fun wire(t: FakeBandTransport) {
        t.onData = { channel, opcode, body -> onData(channel, opcode, body) }
    }

    /** Nova conexão (para testar a reconexão): transporte novo e autenticação do zero. */
    fun newTransport(): FakeBandTransport {
        authenticated = false
        connections++
        return FakeBandTransport().also {
            wire(it)
            transport = it
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ctr(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        }.doFinal(data)

    private fun deriveKeys() {
        val hk = hmac(phoneNonce + bandNonce, authKey)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < 64) {
            t = hmac(hk, t + "miwear-auth".toByteArray() + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        val okm = out.toByteArray()
        hostDecKey = okm.copyOfRange(0, 16)
        hostEncKey = okm.copyOfRange(16, 32)
        hostDecNonce = okm.copyOfRange(32, 36)
        hostEncNonce = okm.copyOfRange(36, 40)
        lastV1Counter = 0
    }

    private var hostDecNonce = ByteArray(0)

    /** Último contador de nonce visto num comando V1 cifrado (precisa crescer). */
    private var lastV1Counter = 0

    private fun ccmEncrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        @Suppress("DEPRECATION")
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(true, AEADParameters(KeyParameter(key), 32, nonce, null))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val n = cipher.processBytes(data, 0, data.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    private fun v1Nonce(base: ByteArray, counter: Int) =
        base + ByteArray(4) + java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(counter).array()

    /** Protocolo "V1" (SPP V1 ou BLE V1): AES-CCM no lugar do AES-CTR do V2. */
    protected open val usesV1Crypto: Boolean get() = transport.isV1

    private fun ccmDecrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        @Suppress("DEPRECATION")
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(false, AEADParameters(KeyParameter(key), 32, nonce, null))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val n = cipher.processBytes(data, 0, data.size, out, 0)
        cipher.doFinal(out, n) // lança se a tag não conferir
        return out
    }

    /** Envia um comando da banda para o celular (cifrado se já autenticado). */
    fun reply(command: Command) {
        val bytes = command.toByteArray()
        val encrypt = authenticated && command.type != 1
        if (usesV1Crypto) {
            // XiaomiAuthService.decrypt (lado do celular) usa sempre o contador 0.
            if (encrypt) sendToHostV1(SppV2.CHANNEL_PROTOBUF, true, ccmEncrypt(hostDecKey, v1Nonce(hostDecNonce, 0), bytes))
            else sendToHostV1(SppV2.CHANNEL_PROTOBUF, false, bytes)
        } else if (encrypt) {
            transport.sendToHost(SppV2.CHANNEL_PROTOBUF, SppV2.OPCODE_ENCRYPTED, ctr(hostDecKey, bytes))
        } else {
            transport.sendToHost(SppV2.CHANNEL_PROTOBUF, SppV2.OPCODE_PLAIN, bytes)
        }
    }

    /** Envia um pacote V1 (o canal e o "cifrado" são traduzidos por cada transporte). */
    protected open fun sendToHostV1(channel: Int, encrypted: Boolean, body: ByteArray) {
        transport.sendToHost(channel, if (encrypted) SppV1.DATA_TYPE_ENCRYPTED else SppV1.DATA_TYPE_PLAIN, body)
    }

    /**
     * Pacote V1 recebido: comandos cifrados levam o contador (u16 LE) na frente; o canal de arquivos
     * vai cifrado com o contador 0 (XiaomiSppPacketV1.encode / XiaomiCharacteristicV1).
     */
    protected fun onV1Data(channel: Int, encrypted: Boolean, body: ByteArray, counterPrefixed: Boolean = true) {
        if (!encrypted) {
            check(channel == SppV2.CHANNEL_PROTOBUF) { "arquivo sem cifra no V1" }
            val command = Command.parseFrom(body)
            check(command.type == 1) { "comando ${command.type} sem cifra" }
            commandsReceived += command
            handleCommand(command)
            return
        }
        check(authenticated) { "pacote cifrado antes da autenticação" }
        when (channel) {
            SppV2.CHANNEL_PROTOBUF -> {
                val counter: Int
                val ct: ByteArray
                if (counterPrefixed) {
                    counter = (body[0].toInt() and 0xff) or ((body[1].toInt() and 0xff) shl 8)
                    ct = body.copyOfRange(2, body.size)
                } else {
                    error("comando cifrado sem contador")
                }
                check(counter > lastV1Counter) { "contador do nonce não cresceu ($counter depois de $lastV1Counter)" }
                lastV1Counter = counter
                val command = Command.parseFrom(ccmDecrypt(hostEncKey, v1Nonce(hostEncNonce, counter), ct))
                commandsReceived += command
                handleCommand(command)
            }
            SppV2.CHANNEL_DATA -> onDataChannel(ccmDecrypt(hostEncKey, v1Nonce(hostEncNonce, 0), body))
        }
    }

    private fun onData(channel: Int, opcode: Int, body: ByteArray) {
        if (usesV1Crypto) {
            // SPP V1: opcode = dataType; comandos de autenticação vêm com dataType 2.
            check(opcode != SppV1.DATA_TYPE_ENCRYPTED || channel == SppV2.CHANNEL_DATA || authenticated) { "cifrado antes da autenticação" }
            if (opcode == SppV1.DATA_TYPE_AUTH) check(channel == SppV2.CHANNEL_PROTOBUF)
            onV1Data(channel, opcode == SppV1.DATA_TYPE_ENCRYPTED, body)
            return
        }
        when (channel) {
            SppV2.CHANNEL_PROTOBUF -> {
                val plain = if (opcode == SppV2.OPCODE_ENCRYPTED) {
                    check(authenticated) { "comando cifrado antes da autenticação" }
                    ctr(hostEncKey, body)
                } else {
                    body
                }
                val command = Command.parseFrom(plain)
                if (command.type != 1) check(opcode == SppV2.OPCODE_ENCRYPTED) { "comando ${command.type} sem cifra" }
                commandsReceived += command
                handleCommand(command)
            }
            SppV2.CHANNEL_DATA -> onDataChannel(body)
        }
    }

    protected open fun onDataChannel(body: ByteArray) = Unit

    protected open fun handleCommand(command: Command) {
        when {
            command.type == 1 && command.subtype == 26 -> {
                if (refuseNoBound) {
                    reply(Command.newBuilder().setType(1).setSubtype(26).setAuth(Account.newBuilder().setErrorCode(4)).build())
                    return
                }
                phoneNonce = command.auth.appVerify.nonce.toByteArray()
                bandNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                deriveKeys()
                val bandHmac = hmac(hostDecKey, bandNonce + phoneNonce)
                reply(
                    Command.newBuilder().setType(1).setSubtype(26).setAuth(
                        Account.newBuilder().setDeviceVerify(
                            Auth.DeviceVerify.newBuilder()
                                .setNonce(ByteString.copyFrom(bandNonce))
                                .setHmac(ByteString.copyFrom(bandHmac)),
                        ),
                    ).build(),
                )
            }
            command.type == 1 && command.subtype == 27 -> {
                val confirm = command.auth.appConfirm
                val expected = hmac(hostEncKey, phoneNonce + bandNonce)
                check(expected.contentEquals(confirm.encryptedNonces.toByteArray())) { "encryptedNonces errado" }
                val nonce = hostEncNonce + ByteArray(8)
                companionReceived = CompanionDevice.parseFrom(ccmDecrypt(hostEncKey, nonce, confirm.encryptedDeviceInfo.toByteArray()))
                reply(Command.newBuilder().setType(1).setSubtype(27).setAuth(Account.newBuilder().setAuthStep4(Auth.DeviceConfirm.newBuilder().setUnknown1(true))).build())
                authenticated = true
            }
            command.type == 2 && command.subtype == 2 -> reply(
                Command.newBuilder().setType(2).setSubtype(2).setSystem(
                    System.newBuilder().setDeviceInfo(
                        DeviceInfo.newBuilder().setModel("Xiaomi Smart Band 10").setFirmware("3.0.1").setSerialNumber("TESTE"),
                    ),
                ).build(),
            )
            command.type == 2 && command.subtype == 1 -> reply(
                Command.newBuilder().setType(2).setSubtype(1).setSystem(
                    System.newBuilder().setPower(Power.newBuilder().setBattery(Battery.newBuilder().setLevel(77))),
                ).build(),
            )
        }
    }
}
