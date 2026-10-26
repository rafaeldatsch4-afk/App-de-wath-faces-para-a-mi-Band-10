package io.github.rafaeldatsch4afk.mibandfaces.key

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda a auth key cifrada com uma chave AES-256-GCM do Android Keystore. A chave do Keystore
 * nunca sai do hardware/TEE; nas preferências fica só o texto cifrado e o IV.
 */
class AuthKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth_key", Context.MODE_PRIVATE)

    fun save(normalizedKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(normalizedKey.toByteArray(Charsets.US_ASCII))
        prefs.edit {
            putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(PREF_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    /** Retorna a chave salva, ou null. Se o Keystore não conseguir decifrar, apaga o registro. */
    fun load(): String? {
        val iv = prefs.getString(PREF_IV, null) ?: return null
        val data = prefs.getString(PREF_DATA, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.US_ASCII)
        } catch (e: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit {
            remove(PREF_IV)
            remove(PREF_DATA)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "miband10_auth_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_IV = "iv"
        const val PREF_DATA = "data"
    }
}
