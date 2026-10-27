package org.iurl.litegallery

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the passwords of saved network servers (AES-256-GCM).
 *
 * The key is created by the Android Keystore and never leaves it: it cannot be read out of the
 * app's data, so a copy of the saved settings is of no use without this device. The encrypted
 * text is "<12 bytes IV><ciphertext>" in Base64.
 */
object PasswordCipher {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "litegallery_network_passwords"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Where the key comes from; replaceable so tests do not need the Android Keystore. */
    internal var keyProvider: () -> SecretKey = { keystoreKey() }

    /** The encrypted form of [plain] (empty stays empty), or null if encrypting failed. */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        // Test builds can switch the encryption off (see build.gradle); null means "not encrypted".
        if (!BuildConfig.ENCRYPT_PASSWORDS) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("PasswordCipher", "Could not encrypt", e)
            null
        }
    }

    /**
     * The password hidden in [encoded] (empty stays empty), or null if it cannot be read, for
     * example because the key was lost after a restore on another device.
     */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        if (!BuildConfig.ENCRYPT_PASSWORDS) return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("PasswordCipher", "Could not decrypt", e)
            null
        }
    }

    @Synchronized
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
