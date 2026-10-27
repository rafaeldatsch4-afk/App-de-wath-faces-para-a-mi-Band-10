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
        hostEncNonce = okm.copyOfRange(36, 40)
    }

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
        if (authenticated && command.type != 1) {
            transport.sendToHost(SppV2.CHANNEL_PROTOBUF, SppV2.OPCODE_ENCRYPTED, ctr(hostDecKey, bytes))
        } else {
            transport.sendToHost(SppV2.CHANNEL_PROTOBUF, SppV2.OPCODE_PLAIN, bytes)
        }
    }

    private fun onData(channel: Int, opcode: Int, body: ByteArray) {
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
