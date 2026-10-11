package org.deadnet.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Cryptographic operations for DeadNet:
 * - 32-byte X25519 keypair generation
 * - 4-byte truncated Node ID derivation
 * - Authenticated asymmetric encryption (X25519 + HKDF-SHA256 + ChaCha20-Poly1305 sealed box)
 * - Authenticated decryption
 */
object DeadNetCrypto {

    private val secureRandom = SecureRandom()

    data class KeyPair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    ) {
        val nodeId: String
            get() = deriveNodeId(publicKey)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KeyPair
            return publicKey.contentEquals(other.publicKey) && privateKey.contentEquals(other.privateKey)
        }

        override fun hashCode(): Int {
            return 31 * publicKey.contentHashCode() + privateKey.contentHashCode()
        }
    }

    fun generateKeyPair(): KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val pub = (keyPair.public as X25519PublicKeyParameters).encoded
        val priv = (keyPair.private as X25519PrivateKeyParameters).encoded

        return KeyPair(publicKey = pub, privateKey = priv)
    }

    /**
     * Derives a truncated 4-byte Node ID from a 32-byte public key.
     * Expressed as an 8-character uppercase hex string (e.g., "7F2A09B1").
     */
    fun deriveNodeId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        val truncated = hash.sliceArray(0 until 4)
        return truncated.joinToString("") { "%02X".format(Locale.ROOT, it) }
    }

    /**
     * Returns 4 raw bytes representing the Node ID.
     */
    fun deriveNodeIdBytes(publicKey: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.sliceArray(0 until 4)
    }

    /**
     * Encrypts plaintext for a recipient using a Libsodium sealed-box equivalent:
     * - Generates ephemeral X25519 keypair
     * - Derives shared secret with recipient's public key
     * - Derives 32-byte AES/ChaCha key via HKDF-SHA256
     * - Encrypts with ChaCha20-Poly1305 (12-byte random nonce, 16-byte Poly1305 MAC)
     * Format: [32-byte ephemeral pubkey] + [12-byte nonce] + [ciphertext + 16-byte tag]
     */
    fun encrypt(recipientPubKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPubKey.size == 32) { "Recipient public key must be 32 bytes" }

        // 1. Generate ephemeral keypair
        val ephemeral = generateKeyPair()
        val ephemeralPriv = X25519PrivateKeyParameters(ephemeral.privateKey, 0)
        val recipientPub = X25519PublicKeyParameters(recipientPubKey, 0)

        // 2. Compute shared secret
        val agreement = X25519Agreement()
        agreement.init(ephemeralPriv)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(recipientPub, sharedSecret, 0)

        // 3. Derive key via HKDF
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val salt = ephemeral.publicKey // Ephemeral pubkey acts as salt
        val info = "DeadNet-Payload-v1".toByteArray(Charsets.UTF_8)
        hkdf.init(HKDFParameters(sharedSecret, salt, info))

        val symmetricKey = ByteArray(32)
        hkdf.generateBytes(symmetricKey, 0, 32)

        // 4. Generate 12-byte nonce
        val nonce = ByteArray(12)
        secureRandom.nextBytes(nonce)

        // 5. Encrypt with ChaCha20-Poly1305
        val cipher = ChaCha20Poly1305()
        cipher.init(true, ParametersWithIV(KeyParameter(symmetricKey), nonce))

        val outBuf = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, outBuf, 0)
        cipher.doFinal(outBuf, len)

        // Total: 32 (ephemeral pub) + 12 (nonce) + outBuf
        return ephemeral.publicKey + nonce + outBuf
    }

    /**
     * Decrypts ciphertext encrypted with [encrypt].
     */
    fun decrypt(recipientPrivateKey: ByteArray, sealedPayload: ByteArray): ByteArray {
        require(recipientPrivateKey.size == 32) { "Recipient private key must be 32 bytes" }
        val minSize = 32 + 12 + 16 // Ephemeral pub + nonce + Poly1305 tag
        require(sealedPayload.size >= minSize) { "Sealed payload too short (${sealedPayload.size} < $minSize)" }

        val ephemeralPubBytes = sealedPayload.sliceArray(0 until 32)
        val nonce = sealedPayload.sliceArray(32 until 44)
        val ciphertext = sealedPayload.sliceArray(44 until sealedPayload.size)

        // 1. Compute shared secret
        val recipientPriv = X25519PrivateKeyParameters(recipientPrivateKey, 0)
        val ephemeralPub = X25519PublicKeyParameters(ephemeralPubBytes, 0)

        val agreement = X25519Agreement()
        agreement.init(recipientPriv)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(ephemeralPub, sharedSecret, 0)

        // 2. Derive key via HKDF
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val salt = ephemeralPubBytes
        val info = "DeadNet-Payload-v1".toByteArray(Charsets.UTF_8)
        hkdf.init(HKDFParameters(sharedSecret, salt, info))

        val symmetricKey = ByteArray(32)
        hkdf.generateBytes(symmetricKey, 0, 32)

        // 3. Decrypt with ChaCha20-Poly1305
        val cipher = ChaCha20Poly1305()
        cipher.init(false, ParametersWithIV(KeyParameter(symmetricKey), nonce))

        val outBuf = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, outBuf, 0)
        val finalLen = cipher.doFinal(outBuf, len)

        return outBuf.sliceArray(0 until (len + finalLen))
    }

    /**
     * Helper to encode byte array to hex string.
     */
    fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(Locale.ROOT, it) }
    }

    /**
     * Helper to parse hex string to byte array.
     */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(" ", "")
        val len = clean.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            result[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }
}
