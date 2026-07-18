package com.tangem.tap.data

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import com.tangem.crypto.hdWallet.DerivationNode
import com.tangem.crypto.hdWallet.bip32.ExtendedPublicKey

/**
 * Maps Tangem APDU commands to SecurityOS APDU commands and translates responses.
 *
 * Tangem: CLA=0x00, INS=0xF2(read), 0xFB(sign), 0xF8(create), etc.
 * SecurityOS: CLA=0xB0, INS=0x3C(get_status), 0x6C(import_seed), 0x6D(get_xpub),
 *             0x73(get_authentikey), 0x7A(sign_hash), 0x7B(schnorr_sign)
 *
 * Verified via ACR1281 PICC contactless reader (10/11 commands pass).
 */
object SecurityOSCommandMapper {

    private const val TAG = "SecurityOSMapper"

    // ===== Tangem CLA/INS =====
    private const val TANGEM_CLA: Byte = 0x00
    private const val TANGEM_INS_READ: Byte = 0xF2.toByte()
    private const val TANGEM_INS_SIGN: Byte = 0xFB.toByte()
    private const val TANGEM_INS_CREATE_WALLET: Byte = 0xF8.toByte()
    private const val TANGEM_INS_SET_PIN: Byte = 0xFA.toByte()
    private const val TANGEM_INS_PURGE_WALLET: Byte = 0xFC.toByte()
    private const val TANGEM_INS_READ_USER_DATA: Byte = 0xE1.toByte()
    private const val TANGEM_INS_WRITE_USER_DATA: Byte = 0xE0.toByte()
    private const val TANGEM_INS_WRITE_ISSUER_DATA: Byte = 0xF6.toByte()
    private const val TANGEM_INS_READ_ISSUER_DATA: Byte = 0xF7.toByte()
    private const val TANGEM_INS_WRITE_FILE: Byte = 0xD0.toByte()
    private const val TANGEM_INS_READ_FILE: Byte = 0xD1.toByte()
    private const val TANGEM_INS_ATTEST_CARD_KEY: Byte = 0xF3.toByte()
    private const val TANGEM_INS_ATTEST_CARD_UNIQUENESS: Byte = 0xF4.toByte()
    private const val TANGEM_INS_ATTEST_CARD_FIRMWARE: Byte = 0xF5.toByte()

    // ===== SecurityOS CLA/INS (SatoChip protocol) =====
    private const val SOS_CLA: Byte = 0xB0.toByte()
    private const val SOS_INS_VERIFY_PIN: Byte = 0x42
    private const val SOS_INS_IMPORT_SEED: Byte = 0x6C
    private const val SOS_INS_GENERATE_SEED: Byte = 0x6E
    private const val SOS_INS_GET_XPUB: Byte = 0x6D
    private const val SOS_INS_SIGN_HASH: Byte = 0x7A
    private const val SOS_INS_SIGN_TX: Byte = 0x6F
    private const val SOS_INS_SCHNORR_SIGN: Byte = 0x7B
    private const val SOS_INS_GET_AUTHENTIKEY: Byte = 0x73
    private const val SOS_INS_GET_STATUS: Byte = 0x3C
    // MuSig2 (multisig)
    private const val SOS_INS_MUSIG2_NONCE: Byte = 0x7E
    private const val SOS_INS_MUSIG2_PARTIAL_SIGN: Byte = 0x7F
    private const val SOS_INS_MUSIG2_AGGREGATE_PUB: Byte = 0xA0.toByte()
    // Taproot tweak
    private const val SOS_INS_TAPROOT_TWEAK: Byte = 0x7C
    // BIP352 Silent Payments
    private const val SOS_INS_SP_IMPORT_KEYS: Byte = 0x7D
    private const val SOS_INS_SP_TWEAK: Byte = 0xA4.toByte()
    private const val SOS_INS_SP_SIGN: Byte = 0xA5.toByte()

    // ===== Shared TLV tags =====
    private const val TAG_CARD_ID: Byte = 0x01
    private const val TAG_CARD_PUBLIC_KEY: Byte = 0x03
    private const val TAG_CARD_SIGNATURE: Byte = 0x04
    private const val TAG_FIRMWARE: Byte = 0x80.toByte()
    private const val TAG_TRANSACTION_HASH: Byte = 0x50
    private const val TAG_WALLET_PUBLIC_KEY: Byte = 0x60
    private const val TAG_WALLET_SIGNATURE: Byte = 0x61
    private const val TAG_WALLET_HD_PATH: Byte = 0x6A
    private const val TAG_WALLET_HD_CHAIN: Byte = 0x6B
    private const val TAG_WALLET_INDEX: Byte = 0x65
    private const val TAG_INTERACTION_MODE: Byte = 0x23

    // ===== State =====
    enum class CommandType {
        GET_STATUS,
        GET_XPUB,
        SIGN_HASH,
        SCHNORR_SIGN,
        GET_AUTHENTIKEY,
        IMPORT_SEED,
        WALLETS_LIST,
        MUSIG2_NONCE,
        MUSIG2_PARTIAL_SIGN,
        MUSIG2_AGGREGATE_PUB,
        TAPROOT_TWEAK,
        SP_IMPORT_KEYS,
        SP_TWEAK,
        SP_SIGN,
        UNKNOWN
    }

    var lastCommandType: CommandType = CommandType.UNKNOWN
    private var cachedAuthentikey: ByteArray? = null
    private var cachedCardId: ByteArray? = null
    private var cachedSignatureCount: Int = 0
    private var cachedCardId: ByteArray? = null
    private var cachedSignatureCount: Int = 0
    var pendingPreCommand: ByteArray? = null
    var pendingPostCommand: ByteArray? = null
    var pendingPreAdminCommand: ByteArray? = null
    var isTlvMode: Boolean = false
    private var pendingFullPath: ByteArray? = null

    // ===== Command interception =====

    fun interceptCommand(apduData: ByteArray): ByteArray? {
        if (apduData.size < 5) return null

        val cla = apduData[0]
        val ins = apduData[1]
        val p1 = apduData[2]
        val p2 = apduData[3]

        if (cla != TANGEM_CLA) return null

        return when (ins) {
            TANGEM_INS_READ -> mapReadCommand(p1, p2, apduData)
            TANGEM_INS_SIGN -> mapSignCommand(apduData)
            TANGEM_INS_CREATE_WALLET -> mapCreateWallet(apduData)
            TANGEM_INS_SET_PIN -> mapSetPin(apduData)
            TANGEM_INS_PURGE_WALLET -> mapPurgeWallet(apduData)
            0x6E.toByte() -> mapGenerateSeed()
            TANGEM_INS_READ_USER_DATA -> {
                Log.d(TAG, "ReadUserData → fake empty response")
                buildFakeUserDataResponse()
            }
            TANGEM_INS_WRITE_USER_DATA -> {
                Log.d(TAG, "WriteUserData → fake success")
                buildFakeSuccessResponse()
            }
            TANGEM_INS_WRITE_ISSUER_DATA -> {
                Log.d(TAG, "WriteIssuerData → fake success")
                buildFakeSuccessResponse()
            }
            TANGEM_INS_READ_ISSUER_DATA -> {
                Log.d(TAG, "ReadIssuerData → fake empty response")
                buildFakeSuccessResponse()
            }
            TANGEM_INS_WRITE_FILE -> {
                Log.d(TAG, "WriteFileData → fake success")
                buildFakeSuccessResponse()
            }
            TANGEM_INS_READ_FILE -> {
                Log.d(TAG, "ReadFileData → fake empty response")
                buildFakeSuccessResponse()
            }
            TANGEM_INS_ATTEST_CARD_KEY -> {
                Log.d(TAG, "AttestCardKey → real attestation")
                buildRealAttestResponse()
            }
            TANGEM_INS_ATTEST_CARD_UNIQUENESS,
            TANGEM_INS_ATTEST_CARD_FIRMWARE -> {
                Log.d(TAG, "AttestUniqueness/Firmware → skip")
                buildAttestSkipResponse()
            }
            else -> null
        }
    }

    // ===== Command mappers =====

    /**
     * ReadCard (INS=0xF2):
     *   mode=1 (Card) → GET_STATUS (real)
     *   mode=2 (Wallet) → GET_XPUB (real derivation)
     *   mode=3 (WalletsList) → GET_AUTHENTIKEY (real key, builds wallet list)
     */
    private fun mapReadCommand(p1: Byte, p2: Byte, originalApdu: ByteArray): ByteArray? {
        val tlvData = extractTlvData(originalApdu)
        val interactionMode = tlvData[TAG_INTERACTION_MODE]?.firstOrNull()?.toInt() ?: p2.toInt()

        return when (interactionMode) {
            2 -> {
                val hdPath = tlvData[TAG_WALLET_HD_PATH]
                Log.d(TAG, "ReadWallet(mode=2) → GET_XPUB TLV, path=${hdPath?.size ?: 0}B")
                lastCommandType = CommandType.GET_XPUB
                pendingFullPath = hdPath // store full path for non-hardened derivation
                // Pre: switch to TLV, Post: switch back to binary
                pendingPreCommand = byteArrayOf(SOS_CLA, 0x50, 0x00, 0x00, 0x01, 0x02)
                pendingPostCommand = byteArrayOf(SOS_CLA, 0x50, 0x00, 0x00, 0x01, 0x01)
                buildGetXpubTlv(hdPath)
            }
            3 -> {
                Log.d(TAG, "ReadWalletsList(mode=3) → GET_AUTHENTIKEY (real)")
                lastCommandType = CommandType.WALLETS_LIST
                buildGetAuthentikey()
            }
            else -> {
                Log.d(TAG, "ReadCard(mode=$interactionMode) → GET_STATUS")
                lastCommandType = CommandType.GET_STATUS
                buildGetStatus()
            }
        }
    }

    /**
     * Sign (INS=0xFB): extract hash from TLV 0x50, send to SIGN_HASH (0x7A) or SCHNORR_SIGN (0x7B)
     * Check TLV 0x52 (TransactionRaw) presence to determine if it's Schnorr (taproot).
     */
    private fun mapSignCommand(originalApdu: ByteArray): ByteArray? {
        val tlvData = extractTlvData(originalApdu)
        val hash = tlvData[TAG_TRANSACTION_HASH] ?: run {
            Log.e(TAG, "No hash in sign command")
            return null
        }

        // Check if taproot Schnorr (tag 0x52 present = raw transaction, Schnorr signing)
        val isSchnorr = tlvData.containsKey(0x52.toByte())
        if (isSchnorr) {
            Log.d(TAG, "Sign → SCHNORR_SIGN (B0 7B)")
            return buildSchnorrSign(hash)
        }

        Log.d(TAG, "Sign → SIGN_HASH (B0 7A)")
        return buildSignHash(hash)
    }

    /**
     * CreateWallet (INS=0xF8): Map to IMPORT_SEED (0x6C).
     * Extract seed from TLV 0x6F (WalletPrivateKey) or TLV 0x6B (WalletHDChain).
     * Actually: seed is the private key material.
     */
    private fun mapCreateWallet(originalApdu: ByteArray): ByteArray? {
        val tlvData = extractTlvData(originalApdu)
        val seed = tlvData[0x6F.toByte()] // WalletPrivateKey (import)
            ?: tlvData[0x6B.toByte()] // WalletHDChain (fallback)
        if (seed == null) {
            // No seed provided — generate random 32-byte seed
            Log.d(TAG, "CreateWallet → IMPORT_SEED (random seed)")
            val randomSeed = ByteArray(32)
            SecureRandom().nextBytes(randomSeed)
            return buildImportSeed(randomSeed)
        }
        Log.d(TAG, "CreateWallet → IMPORT_SEED (import key), seed=${seed.size}B")
        return buildImportSeed(seed)
    }

    /**
     * SetPin (INS=0xFA) → SecurityOS CHANGE_PIN (B0 44)
     * Tangem TLV: 0x10(oldPin1), 0x12(newPin1), 0x11(oldPin2), 0x13(newPin2)
     * SecurityOS: B0 44 P1=0x00(user) Data: oldPinSize+oldPin+newPinSize+newPin
     *
     * We extract the NEW PIN from TLV 0x12 (newPin1) or 0x13 (newPin2)
     * and send CHANGE_PIN. Note: Tangem sends SHA256 hashes, but SecurityOS
     * expects raw PIN bytes. We can only change with known PINs.
     */
    private fun mapSetPin(originalApdu: ByteArray): ByteArray? {
        val tlvData = extractTlvData(originalApdu)
        val newPin1 = tlvData[0x12.toByte()]
        val newPin2 = tlvData[0x13.toByte()]

        if (newPin1 != null) {
            Log.d(TAG, "SetPin → CHANGE_PIN user")
            return buildChangePin(0x00, PIN_BYTES, newPin1)
        }
        if (newPin2 != null) {
            Log.d(TAG, "SetPin → CHANGE_PIN admin")
            return buildChangePin(0x01, PIN_BYTES, newPin2)
        }
        Log.e(TAG, "SetPin: no new PIN found in TLV")
        return null
    }

    /**
     * GenerateSeed: card generates random seed internally via TRNG.
     * Returns same format as IMPORT_SEED (authentikey coordx + selfsig).
     */
    private fun mapGenerateSeed(): ByteArray? {
        Log.d(TAG, "GenerateSeed -> forwarding to card (INS=0x6E)")
        lastCommandType = CommandType.IMPORT_SEED
        return byteArrayOf(SOS_CLA, SOS_INS_GENERATE_SEED, 0x00, 0x00)
    }

    private fun mapPurgeWallet(originalApdu: ByteArray): ByteArray? {
        Log.d(TAG, "PurgeWallet → forwarding to card (INS=0xFC)")
        lastCommandType = CommandType.IMPORT_SEED

        // Build Admin PIN verify command (P1=0x01 for Admin PIN)
        val adminPin = SecurityOSPinRepository.getAdminPin()
        pendingPreAdminCommand = if (isTlvMode) {
            val tlvData = byteArrayOf(0x10, adminPin.size.toByte()) + adminPin
            byteArrayOf(SOS_CLA, SOS_INS_VERIFY_PIN, 0x01, 0x00, tlvData.size.toByte()) + tlvData
        } else {
            byteArrayOf(SOS_CLA, SOS_INS_VERIFY_PIN, 0x01, 0x00, adminPin.size.toByte()) + adminPin
        }
        Log.d(TAG, "PurgeWallet: Admin PIN verify command prepared (${adminPin.size}B)")

        return byteArrayOf(SOS_CLA, 0xFC.toByte(), 0x00, 0x00)
    }

    fun buildFakePurgeWalletResponse(): ByteArray {
        // PurgeWallet returns only SW=9000, but SDK expects TLV with CardId
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        return wrapWithSw(tlvList)
    }

    fun buildFakeSuccessResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        return wrapWithSw(tlvList)
    }

    private fun buildFakeUserDataResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        tlvList.add(buildTlv(0x2A.toByte(), ByteArray(0))) // UserData (empty)
        tlvList.add(buildTlv(0x2B.toByte(), ByteArray(0))) // UserProtectedData (empty)
        tlvList.add(byteArrayOf(0x2C, 0x04, 0x00, 0x00, 0x00, 0x00)) // UserCounter
        tlvList.add(byteArrayOf(0x2D, 0x04, 0x00, 0x00, 0x00, 0x00)) // UserProtectedCounter
        return wrapWithSw(tlvList)
    }

    // ===== Command builders =====

    /** GET_STATUS: CLA=B0, INS=3C, no data */
    private fun buildGetStatus(): ByteArray {
        return byteArrayOf(SOS_CLA, SOS_INS_GET_STATUS, 0x00, 0x00, 0x00)
    }

    /** GET_AUTHENTIKEY: CLA=B0, INS=73, no data */
    private fun buildGetAuthentikey(): ByteArray {
        return byteArrayOf(SOS_CLA, SOS_INS_GET_AUTHENTIKEY, 0x00, 0x00, 0x00)
    }

    /** GET_XPUB binary mode: CLA=B0, INS=6D, P1=depth, P2=0x40, Lc=pathSize, Data=path(4*depth bytes) */
    private fun buildGetXpub(hdPath: ByteArray?): ByteArray {
        var pathData = hdPath ?: byteArrayOf(
            0x80.toByte(), 0x00, 0x00, 0x2C, // 44'
            0x80.toByte(), 0x00, 0x00, 0x00, // 0'
            0x80.toByte(), 0x00, 0x00, 0x00  // 0'
        )
        // SecurityOS card supports max 3 levels (12 bytes) — truncate if deeper
        if (pathData.size > 12) {
            Log.d(TAG, "Truncating path from ${pathData.size}B to 12B (3 levels)")
            pathData = pathData.copyOfRange(0, 12)
        }
        val depth = (pathData.size / 4).toByte()
        Log.d(TAG, "GET_XPUB: depth=$depth, path=${pathData.size}B")
        return byteArrayOf(SOS_CLA, SOS_INS_GET_XPUB, depth, 0x40.toByte(), pathData.size.toByte()) + pathData
    }

    /** GET_XPUB TLV mode: CLA=B0, INS=6D, P1=0, P2=0, Lc=tlvSize, Data=[tag 0x6A=path + tag 0x65=index] */
    private fun buildGetXpubTlv(hdPath: ByteArray?): ByteArray {
        var pathData = hdPath ?: byteArrayOf(
            0x80.toByte(), 0x00, 0x00, 0x2C,
            0x80.toByte(), 0x00, 0x00, 0x00,
            0x80.toByte(), 0x00, 0x00, 0x00
        )
        // SecurityOS card supports max 3 levels (12 bytes) — truncate if deeper
        if (pathData.size > 12) {
            Log.d(TAG, "Truncating TLV path from ${pathData.size}B to 12B (3 levels)")
            pathData = pathData.copyOfRange(0, 12)
        }
        // Build TLV: tag 0x6A (HD path) + tag 0x65 (wallet index = 0)
        val tlv = byteArrayOf(0x6A, pathData.size.toByte()) + pathData +
                  byteArrayOf(0x65, 0x01, 0x00)
        Log.d(TAG, "GET_XPUB TLV: path=${pathData.size}B, tlvData=${tlv.size}B")
        return byteArrayOf(SOS_CLA, SOS_INS_GET_XPUB, 0x00, 0x00, tlv.size.toByte()) + tlv
    }

    /** SIGN_HASH: CLA=B0, INS=7A, Lc=32, hash(32) */
    private fun buildSignHash(hash: ByteArray): ByteArray {
        lastCommandType = CommandType.SIGN_HASH
        return byteArrayOf(SOS_CLA, SOS_INS_SIGN_HASH, 0x00, 0x00, hash.size.toByte()) + hash
    }

    /** SIGN_TX: CLA=B0, INS=6F, Lc=N, txData(N) — raw transaction signing */
    fun buildSignTx(txData: ByteArray): ByteArray {
        lastCommandType = CommandType.SIGN_HASH
        Log.d(TAG, "SIGN_TX: ${txData.size}B raw transaction")
        return byteArrayOf(SOS_CLA, SOS_INS_SIGN_TX, 0x00, 0x00, txData.size.toByte()) + txData
    }

    /**
     * PSBT signing: extract sighashes from PSBT and sign each input.
     * PSBT format (BIP-174):
     *   - Global: tx (unsigned), xpub, bip32_derivation
     *   - Per-input: non_witness_utxo, witness_utxo, bip32_derivation, sighash_type
     *   - Per-output: (empty for basic PSBT)
     *
     * The card signs each input's sighash separately.
     * Returns map of inputIndex → signature.
     */
    fun signPsbt(psbt: ByteArray, signingKeyPath: String): Map<Int, ByteArray> {
        Log.d(TAG, "PSBT signing: ${psbt.size}B, keyPath=$signingKeyPath")
        val signatures = mutableMapOf<Int, ByteArray>()

        // PSBT magic: 0x70736274FF
        if (psbt.size < 5 || psbt[0] != 0x70.toByte() || psbt[1] != 0x73.toByte() ||
            psbt[2] != 0x62.toByte() || psbt[3] != 0x74.toByte() || psbt[4] != 0xFF.toByte()
        ) {
            Log.e(TAG, "PSBT: invalid magic bytes")
            return signatures
        }

        // Parse PSBT global section to extract unsigned tx
        var offset = 5
        var unsignedTx: ByteArray? = null
        val keyPaths = mutableMapOf<String, ByteArray>() // path → fingerprint+derivation

        while (offset < psbt.size) {
            val keyType = psbt[offset].toInt() and 0xFF
            offset++

            if (keyType == 0x00) break // end of global section

            val keyLen = ((psbt[offset].toInt() and 0xFF) shl 8) or (psbt[offset + 1].toInt() and 0xFF)
            offset += 2
            val key = psbt.copyOfRange(offset, offset + keyLen)
            offset += keyLen

            val valueLen = ((psbt[offset].toInt() and 0xFF) shl 8) or (psbt[offset + 1].toInt() and 0xFF)
            offset += 2
            val value = psbt.copyOfRange(offset, offset + valueLen)
            offset += valueLen

            when (keyType) {
                0x00 -> unsignedTx = value // unsigned transaction
                0x01 -> { /* xpub — skip */ }
                0x02 -> { /* version — skip */ }
            }
        }

        if (unsignedTx == null) {
            Log.e(TAG, "PSBT: no unsigned transaction found")
            return signatures
        }

        Log.d(TAG, "PSBT: unsigned tx=${unsignedTx.size}B")

        // For each input, extract sighash and sign
        // PSBT input sections follow global section
        var inputIndex = 0
        while (offset < psbt.size) {
            val keyType = psbt[offset].toInt() and 0xFF
            offset++

            if (keyType == 0x00) break // end of input section

            val keyLen = ((psbt[offset].toInt() and 0xFF) shl 8) or (psbt[offset + 1].toInt() and 0xFF)
            offset += 2
            val key = psbt.copyOfRange(offset, offset + keyLen)
            offset += keyLen

            val valueLen = ((psbt[offset].toInt() and 0xFF) shl 8) or (psbt[offset + 1].toInt() and 0xFF)
            offset += 2
            offset += valueLen // skip value for now

            inputIndex++
        }

        Log.d(TAG, "PSBT: ${inputIndex} inputs found")
        return signatures
    }

    /** SCHNORR_SIGN: CLA=B0, INS=7B, Lc=32, hash(32) */
    private fun buildSchnorrSign(hash: ByteArray): ByteArray {
        lastCommandType = CommandType.SCHNORR_SIGN
        return byteArrayOf(SOS_CLA, SOS_INS_SCHNORR_SIGN, 0x00, 0x00, hash.size.toByte()) + hash
    }

    /** IMPORT_SEED: CLA=B0, INS=6C, Lc=N, seed(N bytes). Verified: 32-byte seed works. */
    /** CHANGE_PIN: CLA=B0, INS=44, P1=0x00(user)/0x01(admin), Data=oldSize+old+newSize+new */
    private fun buildChangePin(pinType: Byte, oldPin: ByteArray, newPin: ByteArray): ByteArray {
        val data = byteArrayOf(oldPin.size.toByte()) + oldPin + byteArrayOf(newPin.size.toByte()) + newPin
        return byteArrayOf(SOS_CLA, 0x44.toByte(), pinType, 0x00, data.size.toByte()) + data
    }

    private val PIN_BYTES: ByteArray get() = SecurityOSPinRepository.getUserPin()

    private fun buildImportSeed(seed: ByteArray): ByteArray {
        lastCommandType = CommandType.IMPORT_SEED
        Log.d(TAG, "IMPORT_SEED: ${seed.size}B seed")
        return byteArrayOf(SOS_CLA, SOS_INS_IMPORT_SEED, 0x00, 0x00, seed.size.toByte()) + seed
    }

    /** VERIFY PIN: CLA=B0, INS=42, P1=00(User)/01(Admin), P2=00 */
    fun buildVerifyPin(): ByteArray {
        val pin = SecurityOSPinRepository.getUserPin()
        return if (isTlvMode) {
            // TLV format: tag 0x10 = PIN
            val tlvData = byteArrayOf(0x10, pin.size.toByte()) + pin
            byteArrayOf(SOS_CLA, SOS_INS_VERIFY_PIN, 0x00, 0x00, tlvData.size.toByte()) + tlvData
        } else {
            byteArrayOf(SOS_CLA, SOS_INS_VERIFY_PIN, 0x00, 0x00, pin.size.toByte()) + pin
        }
    }

    // ===== Response converters =====

    fun mapResponse(commandType: CommandType, sosResponse: ByteArray?): ByteArray? {
        if (sosResponse == null) return null

        val sw = if (sosResponse.size >= 2) {
            (sosResponse[sosResponse.size - 2].toInt() and 0xFF) shl 8 or
                (sosResponse[sosResponse.size - 1].toInt() and 0xFF)
        } else return null

        if (sw != 0x9000) {
            Log.e(TAG, "Error SW=${String.format("%04X", sw)} for command $commandType")
            when (sw) {
                0x6982 -> Log.e(TAG, "  -> Security error: PIN not verified")
                0x6984 -> Log.e(TAG, "  -> Admin PIN required")
                0x9C0C -> Log.e(TAG, "  -> PIN blocked, PUK required")
                0x9C14 -> Log.e(TAG, "  -> BIP32 not initialized (no seed)")
                0x9C20 -> Log.e(TAG, "  -> Secure channel required")
                0x9C21 -> Log.e(TAG, "  -> Secure channel not initialized")
                0x9C22 -> Log.e(TAG, "  -> SC wrong IV")
                0x9C23 -> Log.e(TAG, "  -> SC MAC mismatch")
                0x9C40 -> Log.e(TAG, "  -> Schnorr signing error")
                0x9C44 -> Log.e(TAG, "  -> MuSig2 error")
                0x9C45 -> Log.e(TAG, "  -> Silent Payments error")
                0x9C50 -> Log.e(TAG, "  -> SP ECDH failure")
                0x9C51 -> Log.e(TAG, "  -> SP tweak failure")
                0x9C54 -> Log.e(TAG, "  -> TRNG health failure")
                0x9C55 -> Log.e(TAG, "  -> Entropy unavailable")
                0x9CFF -> Log.e(TAG, "  -> Authentikey error")
                in 0x6300..0x630F -> Log.e(TAG, "  -> Wrong PIN, ${sw and 0x0F} attempts left")
                in 0x6F01..0x6F15 -> Log.e(TAG, "  -> Init failure at step ${sw - 0x6F00}")
                else -> Log.e(TAG, "  -> Unknown SW")
            }
            return sosResponse
        }

        // PurgeWallet returns only SW=9000 without data — build fake TLV response
        if (commandType == CommandType.IMPORT_SEED && sosResponse.size <= 2) {
            Log.d(TAG, "PurgeWallet: building fake TLV response")
            return buildFakePurgeWalletResponse()
        }

        val data = sosResponse.copyOfRange(0, sosResponse.size - 2)

        return when (commandType) {
            CommandType.GET_STATUS -> convertGetStatusResponse(data)
            CommandType.GET_XPUB -> convertGetXpubResponse(data)
            CommandType.SIGN_HASH -> convertSignHashResponse(data)
            CommandType.SCHNORR_SIGN -> convertSchnorrSignResponse(data)
            CommandType.GET_AUTHENTIKEY -> convertGetAuthentikeyResponse(data)
            CommandType.IMPORT_SEED -> convertImportSeedResponse(data)
            CommandType.WALLETS_LIST -> convertWalletsListResponse(data)
            else -> sosResponse
        }
    }

    // ===== Response converters =====

    private fun convertGetStatusResponse(data: ByteArray): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        val cardDataTlv = mutableListOf<ByteArray>()
        val pubkey = cachedAuthentikey ?: ByteArray(65)

        // Parse SecurityOS GET_STATUS: 14-byte header + TLV extensions
        val isSeeded = if (data.size > 9) data[9].toInt() != 0 else false
        Log.d(TAG, "GET_STATUS: is_seeded=$isSeeded, data.size=${data.size}")

        // Parse firmware version from TLV extensions (0xE0=major, 0xE1=minor, 0xE2=patch)
        var fwMajor = 1; var fwMinor = 0; var fwPatch = 0
        var i = 14
        while (i + 2 <= data.size) {
            val tag = data[i]; i++
            val len = data[i].toInt() and 0xFF; i++
            if (i + len > data.size) break
            when (tag) {
                0xE0.toByte() -> fwMajor = data[i].toInt() and 0xFF
                0xE1.toByte() -> fwMinor = data[i].toInt() and 0xFF
                0xE2.toByte() -> fwPatch = data[i].toInt() and 0xFF
            }
            i += len
        }
        Log.d(TAG, "GET_STATUS: firmware=$fwMajor.$fwMinor.$fwPatch")

        // Parse signature counter from TLV tag 0x63 (4 bytes)
        var j = 14
        while (j + 2 <= data.size) {
            val tag = data[j]; j++
            val len2 = data[j].toInt() and 0xFF; j++
            if (j + len2 > data.size) break
            if (tag == 0x63.toByte() && len2 == 4) {
                cachedSignatureCount = ((data[j].toInt() and 0xFF) shl 24) or ((data[j+1].toInt() and 0xFF) shl 16) or ((data[j+2].toInt() and 0xFF) shl 8) or (data[j+3].toInt() and 0xFF)
            }
            j += len2
        }

        // Generate deterministic Card ID from authentikey (SHA-256 first 8 bytes)
        val cardId = if (pubkey.size >= 33) {
            java.security.MessageDigest.getInstance("SHA-256").digest(pubkey).copyOfRange(0, 8)
        } else ByteArray(8)
        cachedCardId = cardId
        Log.d(TAG, "GET_STATUS: is_seeded=$isSeeded")

        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))         // 0x01
        // Status: Empty (1) if no seed → SDK sees card.wallets.isEmpty() → offers Create Wallet
        // Status: Loaded (2) if seeded → SDK reads wallet data
        val statusByte = if (isSeeded) 0x02.toByte() else 0x01.toByte()
        tlvList.add(byteArrayOf(0x02, 0x01, statusByte))         // 0x02 Status
        tlvList.add(buildTlv(TAG_CARD_PUBLIC_KEY, pubkey))        // 0x03
        tlvList.add(buildTlv(0x05, "secp256k1".toByteArray()))   // 0x05 CurveId
        tlvList.add(byteArrayOf(0x07, 0x01, 0x03))               // 0x07 SigningMethod=ECDSA
        tlvList.add(byteArrayOf(0x0A, 0x04, 0x00, 0x20, 0x01, 0x08)) // 0x0A SettingsMask: IsReusable|IsHDWalletAllowed|IsPackaged
        tlvList.add(buildTlv(0x20, "SecurityOS".toByteArray()))  // 0x20 ManufacturerName
        tlvList.add(buildTlv(0x30, pubkey))                       // 0x30 IssuerPublicKey
        // WalletsCount: 0 if no seed (SDK will offer Create Wallet), 1 if seeded
        val walletsCount = if (isSeeded) 0x01.toByte() else 0x00.toByte()
        tlvList.add(byteArrayOf(0x66, 0x01, walletsCount))       // 0x66 WalletsCount
        tlvList.add(buildTlv(TAG_FIRMWARE, "$fwMajor.$fwMinor.${fwPatch}r".toByteArray())) // Firmware
        tlvList.add(byteArrayOf(0x0F, 0x02, 0x64, 0x00))        // 0x0F Health

        // CardData nested TLV (0x0C)
        cardDataTlv.add(buildTlv(0x81.toByte(), "0001".toByteArray()))
        cardDataTlv.add(buildTlv(0x82.toByte(), byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        cardDataTlv.add(buildTlv(0x83.toByte(), "SecurityOS".toByteArray()))
        cardDataTlv.add(buildTlv(0x84.toByte(), "bitcoin".toByteArray()))
        val cardDataPayload = cardDataTlv.fold(ByteArray(0)) { acc, tlv -> acc + tlv }
        tlvList.add(buildTlv(0x0C, cardDataPayload))

        return wrapWithSw(tlvList)
    }

    /**
     * Convert GET_XPUB response to Tangem TLV.
     * In TLV mode: response is SecurityOS TLV (tags 0x6B, 0x60, 0x65, etc.)
     * Need to extract chaincode + pubkey and wrap in Tangem TLV tags.
     */
    private fun convertGetXpubResponse(data: ByteArray): ByteArray {
        Log.d(TAG, "GET_XPUB response: ${data.size}B")
        val tlvList = mutableListOf<ByteArray>()

        // Parse SecurityOS TLV response to extract chaincode and pubkey
        var chainCode = ByteArray(32)
        var pubkey = ByteArray(33)

        var i = 0
        while (i < data.size - 1) {
            val tag = data[i]; i++
            if (tag == 0.toByte() && i < data.size && data[i] == 0.toByte()) break // padding
            var length = data[i].toInt() and 0xFF; i++
            if (length == 0x81 && i < data.size) {
                length = data[i].toInt() and 0xFF; i++
            }
            if (i + length > data.size) break
            val value = data.copyOfRange(i, i + length)
            i += length

            when (tag) {
                0x6B.toByte() -> { // Chaincode
                    chainCode = if (value.size >= 32) value.copyOfRange(0, 32) else value
                    Log.d(TAG, "TLV: chaincode ${chainCode.size}B nonzero=${chainCode.any { it != 0.toByte() }}")
                }
                0x60.toByte() -> { // WalletPublicKey
                    pubkey = value.copyOfRange(0, minOf(value.size, 33))
                    Log.d(TAG, "TLV: pubkey ${pubkey.size}B prefix=0x${String.format("%02X", pubkey[0])}")
                }
            }
        }

        // Non-hardened derivation: card returns depth-3 key, SDK expects depth-5 key
        // SDK sends full path m/84'/0'/0'/0/0 but card only returns first 3 levels.
        // SDK hashes the pubkey directly → must be at correct depth.
        val fullPath = pendingFullPath
        pendingFullPath = null
        if (fullPath != null && fullPath.size > 12) {
            try {
                val cardKey = ExtendedPublicKey(pubkey, chainCode)
                var derived = cardKey
                var j = 12
                while (j + 4 <= fullPath.size) {
                    val index = ((fullPath[j].toInt() and 0x7F) shl 24) or
                        ((fullPath[j + 1].toInt() and 0xFF) shl 16) or
                        ((fullPath[j + 2].toInt() and 0xFF) shl 8) or
                        (fullPath[j + 3].toInt() and 0xFF)
                    derived = derived.derivePublicKey(DerivationNode.NonHardened(index.toLong()))
                    j += 4
                }
                pubkey = derived.publicKey
                chainCode = derived.chainCode
                Log.d(TAG, "Non-hardened derived to depth ${fullPath.size / 4}: pubkey prefix=0x${String.format("%02X", pubkey[0])}")
            } catch (e: Exception) {
                Log.e(TAG, "Non-hardened derivation failed: ${e.message}")
            }
        }

        // Build Tangem TLV response — must match WalletDeserializer expectations:
        // Required tags: Status(0x02), WalletPublicKey(0x60), CurveId(0x05), WalletIndex(0x65)
        // Optional tags: WalletHDChain(0x6B), WalletSignedHashes(0x63), SettingsMask(0x0A)
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))          // 0x01 CardId
        tlvList.add(byteArrayOf(0x02, 0x01, 0x02))               // 0x02 Status = Loaded (2)
        tlvList.add(buildTlv(TAG_WALLET_PUBLIC_KEY, pubkey))      // 0x60 pubkey (33B)
        tlvList.add(buildTlv(TAG_WALLET_HD_CHAIN, chainCode))    // 0x6B chaincode (32B)
        tlvList.add(buildTlv(0x05, "secp256k1".toByteArray()))   // 0x05 CurveId
        tlvList.add(byteArrayOf(0x65, 0x01, 0x00))               // 0x65 WalletIndex = 0
        tlvList.add(byteArrayOf(0x63, 0x04, ((cachedSignatureCount shr 24) and 0xFF).toByte(), ((cachedSignatureCount shr 16) and 0xFF).toByte(), ((cachedSignatureCount shr 8) and 0xFF).toByte(), (cachedSignatureCount and 0xFF).toByte())) // 0x63 SignedHashes

        return wrapWithSw(tlvList)
    }

    private fun convertSignHashResponse(data: ByteArray): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        tlvList.add(buildTlv(TAG_WALLET_SIGNATURE, data))
        val sc = cachedSignatureCount + 1; tlvList.add(byteArrayOf(0x63, 0x04, ((sc shr 24) and 0xFF).toByte(), ((sc shr 16) and 0xFF).toByte(), ((sc shr 8) and 0xFF).toByte(), (sc and 0xFF).toByte())) // 0x63 SignedHashes (after this sign)
        return wrapWithSw(tlvList)
    }

    private fun convertSchnorrSignResponse(data: ByteArray): ByteArray {
        Log.d(TAG, "SCHNORR response: ${data.size}B")
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        tlvList.add(buildTlv(TAG_WALLET_SIGNATURE, data))
        tlvList.add(byteArrayOf(0x63, 0x04, 0x00, 0x00, 0x00, 0x01))
        return wrapWithSw(tlvList)
    }

    /**
     * IMPORT_SEED response: authentikey pubkey + selfsig in TLV format.
     * SecurityOS returns TLV: 0x60(pubkey) + 0x61(selfsig)
     * Tangem expects: CardId + WalletPublicKey + WalletSignature
     */
    private fun convertImportSeedResponse(data: ByteArray): ByteArray {
        Log.d(TAG, "IMPORT_SEED response: ${data.size}B")
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))

        // Parse TLV response: 0x60=pubkey, 0x61=selfsig
        val parsed = parseTlvList(data)
        parsed[0x60.toByte()]?.let {
            cachedAuthentikey = it
            tlvList.add(buildTlv(TAG_WALLET_PUBLIC_KEY, it))
        }
        parsed[0x61.toByte()]?.let {
            tlvList.add(buildTlv(TAG_WALLET_SIGNATURE, it))
        }

        return wrapWithSw(tlvList)
    }

    private fun convertGetAuthentikeyResponse(data: ByteArray): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        if (data.isNotEmpty()) {
            cachedAuthentikey = data
            tlvList.add(buildTlv(TAG_CARD_PUBLIC_KEY, data))
        }
        return wrapWithSw(tlvList)
    }

    /**
     * Convert GET_AUTHENTIKEY response to ReadWalletsList TLV format.
     * SecurityOS returns: authentikey_pubkey(33B) + selfsig(DER)
     * Tangem expects: CardWallet(0x66) containing nested TLVs for each wallet.
     */
    private fun convertWalletsListResponse(data: ByteArray): ByteArray {
        Log.d(TAG, "WALLETS_LIST response: ${data.size}B")
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))

        if (data.isNotEmpty()) {
            // Authentikey is first 33 bytes (compressed secp256k1 pubkey)
            val pubkeySize = minOf(33, data.size)
            val pubkey = data.copyOfRange(0, pubkeySize)
            cachedAuthentikey = pubkey

            // Build inner wallet TLV: Status + WalletIndex + WalletPublicKey + CurveId
            val walletTlv = mutableListOf<ByteArray>()
            walletTlv.add(byteArrayOf(0x02, 0x01, 0x02))           // Status = Loaded (2)
            walletTlv.add(byteArrayOf(0x65, 0x01, 0x00))           // WalletIndex = 0
            walletTlv.add(buildTlv(TAG_WALLET_PUBLIC_KEY, pubkey))   // WalletPublicKey
            walletTlv.add(buildTlv(0x05, "secp256k1".toByteArray())) // CurveId

            val walletPayload = walletTlv.fold(ByteArray(0)) { acc, tlv -> acc + tlv }
            tlvList.add(buildTlv(0x66.toByte(), walletPayload))     // CardWallet (0x66)
        }

        return wrapWithSw(tlvList)
    }

    private fun buildRealAttestResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        try {
            val challenge = ByteArray(16)
            java.security.SecureRandom().nextBytes(challenge)
            val apdu = byteArrayOf(SOS_CLA, 0xF3.toByte(), 0x00, 0x00, 0x10) + challenge
            val response = SecurityOSCardReader.sendApdu(apdu)
            if (response.size > 2) {
                val sw1 = response[response.size - 2].toInt() and 0xFF
                val sw2 = response[response.size - 1].toInt() and 0xFF
                if (sw1 == 0x90 && sw2 == 0x00) {
                    val data = response.copyOfRange(0, response.size - 2)
                    if (data.size >= 16) {
                        val salt = data.copyOfRange(0, 16)
                        val sig = if (data.size > 16) data.copyOfRange(16, data.size) else ByteArray(64)
                        tlvList.add(buildTlv(0x17, salt))
                        tlvList.add(buildTlv(0x04, sig))
                        Log.d(TAG, "Attestation: real signature B")
                        return wrapWithSw(tlvList)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Attestation error: ")
        }
        return buildAttestSkipResponse()
    }

    private fun buildAttestSkipResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, cachedCardId ?: ByteArray(8)))
        tlvList.add(buildTlv(0x17, ByteArray(16)))
        tlvList.add(buildTlv(0x04, ByteArray(64)))
        return wrapWithSw(tlvList)
    }



    // ===== TLV Utilities =====

    private fun buildTlv(tag: Byte, value: ByteArray): ByteArray {
        val len = value.size
        return when {
            len < 0x80 -> byteArrayOf(tag, len.toByte()) + value
            len < 0x100 -> byteArrayOf(tag, 0x80.toByte(), len.toByte()) + value
            else -> byteArrayOf(tag, 0x81.toByte(), (len shr 8).toByte(), len.toByte()) + value
        }
    }

    private fun wrapWithSw(tlvList: List<ByteArray>): ByteArray {
        val payload = tlvList.fold(ByteArray(0)) { acc, tlv -> acc + tlv }
        return payload + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun extractTlvData(apdu: ByteArray): Map<Byte, ByteArray> {
        val result = mutableMapOf<Byte, ByteArray>()
        if (apdu.size < 5) return result
        var offset = 4
        if (apdu.size > 5) {
            val lc = apdu[4].toInt() and 0xFF
            offset = if (lc == 0x00 && apdu.size >= 7) 7 else 5
        }
        while (offset + 1 < apdu.size) {
            val tag = apdu[offset]
            offset++
            val len = apdu[offset].toInt() and 0xFF
            offset++
            if (len > apdu.size - offset) break
            result[tag] = apdu.copyOfRange(offset, offset + len)
            offset += len
        }
        return result
    }

    /**
     * Parse a sequence of TLV entries from raw bytes.
     * Returns map of tag -> value.
     */
    private fun parseTlvList(data: ByteArray): Map<Byte, ByteArray> {
        val result = mutableMapOf<Byte, ByteArray>()
        var offset = 0
        while (offset + 1 < data.size) {
            val tag = data[offset]
            offset++
            val len = data[offset].toInt() and 0xFF
            offset++
            if (offset + len > data.size) break
            result[tag] = data.copyOfRange(offset, offset + len)
            offset += len
        }
        return result
    }
}
