package io.github.rafaeldatsch4afk.mibandfaces.band

import com.google.protobuf.ByteString
import io.github.rafaeldatsch4afk.mibandfaces.proto.Account
import io.github.rafaeldatsch4afk.mibandfaces.proto.Auth
import io.github.rafaeldatsch4afk.mibandfaces.proto.Command
import io.github.rafaeldatsch4afk.mibandfaces.proto.CompanionDevice
import io.github.rafaeldatsch4afk.mibandfaces.protocol.SessionKeys
import io.github.rafaeldatsch4afk.mibandfaces.protocol.XiaomiCrypto

/** Identidade do celular enviada (cifrada) no passo 2 da autenticação. */
data class CompanionInfo(
    val apiLevel: Int,
    val phoneModel: String,
    /** Duas letras maiúsculas. O Gadgetbridge usa o idioma do aparelho (ex.: "PT"). */
    val region: String,
)

class AuthException(message: String) : Exception(message)

/** Mensagens do handshake "miwear-auth" (XiaomiAuthService no Gadgetbridge). */
object XiaomiAuth {
    const val COMMAND_TYPE = 1
    const val CMD_NONCE = 26
    const val CMD_AUTH = 27

    /** Passo 1: o celular manda o seu nonce de 16 bytes. */
    fun buildNonceCommand(phoneNonce: ByteArray): Command = Command.newBuilder()
        .setType(COMMAND_TYPE)
        .setSubtype(CMD_NONCE)
        .setAuth(
            Account.newBuilder().setAppVerify(
                Auth.AppVerify.newBuilder().setNonce(ByteString.copyFrom(phoneNonce)),
            ),
        )
        .build()

    /** Confere o HMAC da banda: `HMAC(decKey, nonceBanda ‖ nonceCelular)`. */
    fun bandHmacMatches(keys: SessionKeys, phoneNonce: ByteArray, deviceVerify: Auth.DeviceVerify): Boolean {
        val expected = XiaomiCrypto.hmacSha256(keys.decryptionKey, deviceVerify.nonce.toByteArray(), phoneNonce)
        return java.security.MessageDigest.isEqual(expected, deviceVerify.hmac.toByteArray())
    }

    /** Passo 2: prova que o celular conhece a chave e envia a identidade cifrada com AES-CCM. */
    fun buildConfirmCommand(
        keys: SessionKeys,
        phoneNonce: ByteArray,
        bandNonce: ByteArray,
        companion: CompanionInfo,
    ): Command {
        val deviceInfo = CompanionDevice.newBuilder()
            .setUnknown1(0) // 0 = Android
            .setPhoneApiLevel(companion.apiLevel.toFloat())
            .setPhoneName(companion.phoneModel)
            .setUnknown3(224)
            .setRegion(companion.region)
            .build()
        val encryptedNonces = XiaomiCrypto.hmacSha256(keys.encryptionKey, phoneNonce, bandNonce)
        val encryptedDeviceInfo = XiaomiCrypto.aesCcmEncrypt(
            keys.encryptionKey,
            XiaomiCrypto.ccmPacketNonce(keys.encryptionNonce, 0),
            deviceInfo.toByteArray(),
        )
        return Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_AUTH)
            .setAuth(
                Account.newBuilder().setAppConfirm(
                    Auth.AppConfirm.newBuilder()
                        .setEncryptedNonces(ByteString.copyFrom(encryptedNonces))
                        .setEncryptedDeviceInfo(ByteString.copyFrom(encryptedDeviceInfo)),
                ),
            )
            .build()
    }

    /**
     * Texto para os códigos de erro da conta (campo 3 de Account). Nomes do schema do AstroBox,
     * documentados no miband-hack (docs/protocol.md, seção 4.1).
     */
    fun describeAccountError(code: Int): String = when (code) {
        1 -> "HAVE_BOUND: a banda já está vinculada a outro aparelho"
        2 -> "INFO_MISSING: faltam informações no pedido"
        3 -> "INVALID_PARAM: parâmetro inválido"
        4 -> "NO_BOUND: a banda não reconhece este celular como vinculado. " +
            "Ela foi vinculada pelo Mi Fitness em outro aparelho (ex.: iPhone)? Tente vincular pelo Mi Fitness neste celular, " +
            "exportar a chave nova e conectar de novo."
        5 -> "SERVER_ERROR"
        6 -> "CANCEL: cancelado na banda"
        7 -> "VERIFY_ERROR: falha na verificação"
        8 -> "STATE_NOT_MATCH"
        9 -> "USER_CHANGED: a conta da banda mudou"
        10 -> "CONNECTION_REACH_LIMIT: limite de conexões atingido"
        else -> "erro desconhecido"
    }
}
