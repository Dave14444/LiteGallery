package org.iurl.litegallery

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PasswordCipherTest {

    private val originalKeyProvider = PasswordCipher.keyProvider

    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    @Before
    fun setUp() {
        PasswordCipher.keyProvider = { key(0) }
    }

    @After
    fun tearDown() {
        PasswordCipher.keyProvider = originalKeyProvider
    }

    @Test
    fun roundTrip_returnsTheOriginal() {
        val encrypted = PasswordCipher.encrypt("Pa$\$w0rd äöü €")

        assertEquals("Pa$\$w0rd äöü €", PasswordCipher.decrypt(encrypted!!))
    }

    @Test
    fun encryptedTextHidesThePassword() {
        val encrypted = PasswordCipher.encrypt("geheim")!!

        assertNotEquals("geheim", encrypted)
        assertEquals(false, encrypted.contains("geheim"))
    }

    @Test
    fun samePasswordGivesDifferentTextEachTime() {
        assertNotEquals(PasswordCipher.encrypt("geheim"), PasswordCipher.encrypt("geheim"))
    }

    @Test
    fun emptyPasswordStaysEmpty() {
        assertEquals("", PasswordCipher.encrypt(""))
        assertEquals("", PasswordCipher.decrypt(""))
    }

    @Test
    fun wrongKeyOrDamagedTextIsNotDecrypted() {
        val encrypted = PasswordCipher.encrypt("geheim")!!

        PasswordCipher.keyProvider = { key(1) }
        assertNull(PasswordCipher.decrypt(encrypted))

        PasswordCipher.keyProvider = { key(0) }
        assertNull(PasswordCipher.decrypt("kein-base64-!!"))
        assertNull(PasswordCipher.decrypt("QUJD"))
    }
}
