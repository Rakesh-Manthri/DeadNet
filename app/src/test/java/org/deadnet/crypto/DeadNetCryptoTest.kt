package org.deadnet.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeadNetCryptoTest {

    @Test
    fun testKeyPairGenerationAndNodeId() {
        val keyPair1 = DeadNetCrypto.generateKeyPair()
        val keyPair2 = DeadNetCrypto.generateKeyPair()

        assertEquals(32, keyPair1.publicKey.size)
        assertEquals(32, keyPair1.privateKey.size)
        assertEquals(32, keyPair2.publicKey.size)
        assertEquals(32, keyPair2.privateKey.size)

        assertFalse(keyPair1.publicKey.contentEquals(keyPair2.publicKey))
        assertFalse(keyPair1.privateKey.contentEquals(keyPair2.privateKey))

        // Node ID must be 8 hex characters (4 bytes)
        assertEquals(8, keyPair1.nodeId.length)
        assertEquals(8, keyPair2.nodeId.length)
        assertNotEquals(keyPair1.nodeId, keyPair2.nodeId)
    }

    @Test
    fun testEncryptionAndDecryptionRoundtrip() {
        val alice = DeadNetCrypto.generateKeyPair()
        val bob = DeadNetCrypto.generateKeyPair()

        val plaintext = "DeadNet Mesh Secure Payload: Coordinates 37.7749, -122.4194".toByteArray(Charsets.UTF_8)

        // Alice encrypts for Bob
        val sealedCiphertext = DeadNetCrypto.encrypt(bob.publicKey, plaintext)

        // Sealed ciphertext contains 32-byte ephemeral pubkey + 12-byte nonce + ciphertext + 16-byte Poly1305 MAC
        val expectedMinLength = 32 + 12 + plaintext.size + 16
        assertEquals(expectedMinLength, sealedCiphertext.size)

        // Bob decrypts
        val decrypted = DeadNetCrypto.decrypt(bob.privateKey, sealedCiphertext)
        assertArrayEquals(plaintext, decrypted)
        assertEquals(String(plaintext, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testDecryptionFailsWithWrongKey() {
        val bob = DeadNetCrypto.generateKeyPair()
        val charlie = DeadNetCrypto.generateKeyPair()

        val plaintext = "Confidential DTN routing payload".toByteArray(Charsets.UTF_8)
        val sealedCiphertext = DeadNetCrypto.encrypt(bob.publicKey, plaintext)

        try {
            // Charlie attempts to decrypt Bob's message
            DeadNetCrypto.decrypt(charlie.privateKey, sealedCiphertext)
            fail("Expected exception when decrypting with wrong key")
        } catch (_: Exception) {
            // Success: MAC verification failed
        }
    }

    @Test
    fun testTamperedCiphertextFails() {
        val bob = DeadNetCrypto.generateKeyPair()
        val plaintext = "Tamper detection test".toByteArray(Charsets.UTF_8)
        val sealedCiphertext = DeadNetCrypto.encrypt(bob.publicKey, plaintext)

        try {
            DeadNetCrypto.decrypt(bob.privateKey, sealedCiphertext)
            fail("Expected authentication exception on tampered ciphertext")
        } catch (_: Exception) {
            // Expected MAC tag mismatch
        }
    }

    @Test
    fun testBroadcastEncryptionAndDecryption() {
        val plaintext = "Mesh Broadcast to All Neighbors".toByteArray(Charsets.UTF_8)
        val broadcastCiphertext = DeadNetCrypto.encrypt(ByteArray(32) { 0 }, plaintext)

        // Any node can decrypt broadcast message
        val randomNode = DeadNetCrypto.generateKeyPair()
        val decrypted = DeadNetCrypto.decrypt(randomNode.privateKey, broadcastCiphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testHexToBytesSafelyHandlesOddAndEmptyInput() {
        assertArrayEquals(ByteArray(0), DeadNetCrypto.hexToBytes(""))
        assertArrayEquals(byteArrayOf(0x0A), DeadNetCrypto.hexToBytes("A"))
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), DeadNetCrypto.hexToBytes("ABCD"))
    }
}

