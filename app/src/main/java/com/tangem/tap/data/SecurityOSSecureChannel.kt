package com.tangem.tap.data

import android.util.Log
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SCP03 Secure Channel for SecurityOS cards.
 * ECDH key exchange (secp256k1) + AES-128-CBC encryption + HMAC-SHA160 MAC.
 *
 * Matches the firmware's SecureChannel.java implementation.
 * Reference: securityos-javacard/src/securityos/SecureChannel.java
 */
class SecurityOSSecureChannel {

    companion object {
        private const val TAG = "SecurityOSSecureChannel"
        private const val AES_BLOCK_SIZE = 16
        private const val MAC_SIZE = 20 // HMAC-SHA160 = 20 bytes
        private const val IV_RANDOM_SIZE = 12
        private const val IV_COUNTER_SIZE = 4
        private const val IV_SIZE = IV_RANDOM_SIZE + IV_COUNTER_SIZE // 16

        // Key derivation constants (must match firmware)
        private val CST_SC_KEY = "sc_key".toByteArray()
        private val CST_SC_MAC = "sc_mac".toByteArray()

        // INS codes
        val INS_INIT_SC: Byte = 0x81.toByte()
        val INS_PROCESS_SC: Byte = 0x82.toByte()
    }

    private var sessionKey: SecretKey? = null
    private var macKey: SecretKey? = null
    private var ivCounter: Int = 0
    private var ivRandom: ByteArray = ByteArray(IV_RANDOM_SIZE)
    private var isInitialized = false

    fun isInitialized(): Boolean = isInitialized

    /**
     * Build INIT_SECURE_CHANNEL APDU.
     * Sends ephemeral secp256k1 public key to card.
     * Response: cardPubkey(65) + cardSelfsig(DER)
     */
    fun buildInitScCommand(): ByteArray {
        // Generate ephemeral keypair (secp256k1)
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256k1"))
        val keyPair = kpg.generateKeyPair()
        val pubKey = keyPair.public as ECPublicKey

        // Extract uncompressed public key (65 bytes: 04 + X(32) + Y(32))
        val publicPoint = pubKey.w
        val x = publicPoint.affineX.toByteArray()
        val y = publicPoint.affineY.toByteArray()

        // Pad to 32 bytes each
        val xPadded = ByteArray(32)
        val yPadded = ByteArray(32)
        val xStart = maxOf(0, x.size - 32)
        val yStart = maxOf(0, y.size - 32)
        System.arraycopy(x, xStart, xPadded, 32 - (x.size - xStart), x.size - xStart)
        System.arraycopy(y, yStart, yPadded, 32 - (y.size - yStart), y.size - yStart)

        val uncompressedPubKey = ByteArray(65)
        uncompressedPubKey[0] = 0x04
        System.arraycopy(xPadded, 0, uncompressedPubKey, 1, 32)
        System.arraycopy(yPadded, 0, uncompressedPubKey, 33, 32)

        Log.d(TAG, "INIT_SC: ephemeral pubkey=${uncompressedPubKey.size}B")

        // Store for later ECDH computation
        pendingKeyPair = keyPair

        // CLA=0xB0, INS=0x81, P1=0x00, P2=0x00, Lc=65, Data=pubkey
        return byteArrayOf(
            0xB0.toByte(), INS_INIT_SC, 0x00, 0x00, 65.toByte()
        ) + uncompressedPubKey
    }

    private var pendingKeyPair: java.security.KeyPair? = null

    /**
     * Process INIT_SECURE_CHANNEL response.
     * Response: cardPubkey(65) + cardSelfsig(DER)
     * Computes ECDH shared secret and derives session keys.
     */
    fun processInitScResponse(response: ByteArray): Boolean {
        try {
            if (response.size < 65) {
                Log.e(TAG, "INIT_SC response too short: ${response.size}B")
                return false
            }

            val cardPubKeyBytes = response.copyOfRange(0, 65)
            Log.d(TAG, "INIT_SC: card pubkey prefix=0x${String.format("%02X", cardPubKeyBytes[0])}")

            val keyPair = pendingKeyPair ?: run {
                Log.e(TAG, "No pending keypair for ECDH")
                return false
            }

            // ECDH: compute shared secret
            val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
            keyAgreement.init(keyPair.private)
            val cardPubKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(
                    java.security.spec.ECPublicKeySpec(
                        java.security.spec.ECPoint(
                            java.math.BigInteger(1, cardPubKeyBytes.copyOfRange(1, 33)),
                            java.math.BigInteger(1, cardPubKeyBytes.copyOfRange(33, 65))
                        ),
                        (keyPair.private as java.security.interfaces.ECPrivateKey).params
                    )
                )
            keyAgreement.doPhase(cardPubKey, true)
            val sharedSecret = keyAgreement.generateSecret()

            Log.d(TAG, "ECDH: shared secret=${sharedSecret.size}B")

            // Derive session keys via HKDF-like construction
            // Card uses: SHA256(shared_secret) then splits into sessionKey(16) + macKey(20)
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            val derived = sha256.digest(sharedSecret)

            // Session key: first 16 bytes
            val sk = derived.copyOfRange(0, 16)
            sessionKey = SecretKeySpec(sk, "AES")

            // MAC key: SHA256("sc_mac" || derived[16..31])
            val macInput = CST_SC_MAC + derived.copyOfRange(16, 32)
            val mk = sha256.digest(macInput)
            macKey = SecretKeySpec(mk, "HmacSHA160")

            // Generate random IV prefix
            val secureRandom = java.security.SecureRandom()
            secureRandom.nextBytes(ivRandom)
            ivCounter = 0

            isInitialized = true
            Log.d(TAG, "SCP03 initialized: sessionKey + macKey derived")

            // Cleanup
            pendingKeyPair = null
            java.util.Arrays.fill(sharedSecret, 0.toByte())
            java.util.Arrays.fill(sk, 0.toByte())

            return true
        } catch (e: Exception) {
            Log.e(TAG, "INIT_SC failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Wrap APDU command for SCP03.
     * Format: CLA=0x80, INS=0x82, Lc=size+4, Data=[iv(16) + size(2) + mac_size(2) + encrypted(size) + mac(20)]
     */
    fun wrapCommand(apdu: ByteArray): ByteArray {
        if (!isInitialized) return apdu

        try {
            // Increment IV counter
            ivCounter++
            val iv = buildIv()

            // Encrypt the original APDU
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(apdu)

            // Build the SC payload: iv(16) + encrypted_size(2) + encrypted_data + mac
            val payload = ByteArray(IV_SIZE + 2 + encrypted.size + MAC_SIZE)
            System.arraycopy(iv, 0, payload, 0, IV_SIZE)
            payload[IV_SIZE] = (encrypted.size shr 8).toByte()
            payload[IV_SIZE + 1] = (encrypted.size and 0xFF).toByte()
            System.arraycopy(encrypted, 0, payload, IV_SIZE + 2, encrypted.size)

            // Compute MAC: HMAC-SHA160(macKey, iv + encrypted_size + encrypted_data)
            val macData = iv + byteArrayOf(
                (encrypted.size shr 8).toByte(),
                (encrypted.size and 0xFF).toByte()
            ) + encrypted
            val mac = computeMac(macData)
            System.arraycopy(mac, 0, payload, IV_SIZE + 2 + encrypted.size, MAC_SIZE)

            Log.d(TAG, "SC wrap: ${apdu.size}B → ${payload.size}B (iv+enc+mac)")

            // SCP03 APDU: CLA=0x80, INS=0x82, P1=0x00, P2=0x00
            return byteArrayOf(
                0x80.toByte(), INS_PROCESS_SC, 0x00, 0x00,
                payload.size.toByte()
            ) + payload
        } catch (e: Exception) {
            Log.e(TAG, "wrap failed: ${e.message}", e)
            isInitialized = false
            return apdu
        }
    }

    /**
     * Unwrap SCP03 response.
     * Response format: encrypted_data + MAC(20)
     */
    fun unwrapResponse(response: ByteArray): ByteArray {
        if (!isInitialized) return response

        try {
            if (response.size < MAC_SIZE + 1) {
                Log.e(TAG, "SC response too short: ${response.size}B")
                isInitialized = false
                return response
            }

            // Split: encrypted data + MAC
            val encryptedSize = response.size - MAC_SIZE
            val encrypted = response.copyOfRange(0, encryptedSize)
            val receivedMac = response.copyOfRange(encryptedSize, response.size)

            // Verify MAC
            val computedMac = computeMac(encrypted)
            if (!constantTimeEquals(receivedMac, computedMac)) {
                Log.e(TAG, "SC MAC mismatch! Resetting channel")
                isInitialized = false
                return response
            }

            // Decrypt
            val iv = buildIv()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(encrypted)

            Log.d(TAG, "SC unwrap: ${response.size}B → ${decrypted.size}B")
            return decrypted
        } catch (e: Exception) {
            Log.e(TAG, "unwrap failed: ${e.message}", e)
            isInitialized = false
            return response
        }
    }

    /**
     * Build 16-byte IV: random(12) + counter(4, big-endian)
     */
    private fun buildIv(): ByteArray {
        val iv = ByteArray(AES_BLOCK_SIZE)
        System.arraycopy(ivRandom, 0, iv, 0, IV_RANDOM_SIZE)
        iv[12] = (ivCounter shr 24).toByte()
        iv[13] = (ivCounter shr 16).toByte()
        iv[14] = (ivCounter shr 8).toByte()
        iv[15] = (ivCounter and 0xFF).toByte()
        return iv
    }

    /**
     * Compute HMAC-SHA160 (truncated to 20 bytes).
     * Matches firmware: HMAC-SHA1 → 20 bytes.
     */
    private fun computeMac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(macKey)
        return mac.doFinal(data)
    }

    /**
     * Constant-time byte array comparison.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    fun reset() {
        sessionKey = null
        macKey = null
        ivCounter = 0
        ivRandom = ByteArray(IV_RANDOM_SIZE)
        isInitialized = false
        pendingKeyPair = null
        Log.d(TAG, "SCP03 reset")
    }
}
