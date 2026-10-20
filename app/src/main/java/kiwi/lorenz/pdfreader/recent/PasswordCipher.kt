package kiwi.lorenz.pdfreader.recent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the PDF passwords the user chose to remember, see [PasswordCipher]. */
interface PasswordEncryption {
    /** Returns null if encrypting fails; the password then just isn't remembered. */
    fun encrypt(password: String): String?

    /** Returns null if the value can't be decrypted, e.g. after the key was lost. */
    fun decrypt(value: String): String?
}

/**
 * Encrypts remembered PDF passwords with an AES key that lives in the Android Keystore, so the
 * stored value is useless outside this app on this device. The key is not part of backups.
 */
object PasswordCipher : PasswordEncryption {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pdf_passwords"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    override fun encrypt(password: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(password.toByteArray())
        "${cipher.iv.toBase64()}:${encrypted.toBase64()}"
    } catch (_: Exception) {
        null
    }

    override fun decrypt(value: String): String? = try {
        val (iv, encrypted) = value.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        String(cipher.doFinal(encrypted))
    } catch (_: Exception) {
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun ByteArray.toBase64() = Base64.encodeToString(this, Base64.NO_WRAP)
}
