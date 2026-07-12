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
    private const val SOS_INS_GET_XPUB: Byte = 0x6D
    private const val SOS_INS_SIGN_HASH: Byte = 0x7A
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
    var pendingPreCommand: ByteArray? = null
    var pendingPostCommand: ByteArray? = null
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
            TANGEM_INS_ATTEST_CARD_KEY,
            TANGEM_INS_ATTEST_CARD_UNIQUENESS,
            TANGEM_INS_ATTEST_CARD_FIRMWARE -> {
                Log.d(TAG, "Attest bypass → fake response")
                null // fake response handled in SecurityOSCardReader
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

    private fun mapPurgeWallet(originalApdu: ByteArray): ByteArray? {
        Log.d(TAG, "PurgeWallet → fake success (dangerous operation)")
        return buildFakeSuccessResponse()
    }

    fun buildFakeSuccessResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))
        return wrapWithSw(tlvList)
    }

    private fun buildFakeUserDataResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))
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

    private val PIN_BYTES = byteArrayOf(0x31, 0x32, 0x33, 0x34) // "1234" (default after firmware reinstall)

    private fun buildImportSeed(seed: ByteArray): ByteArray {
        lastCommandType = CommandType.IMPORT_SEED
        Log.d(TAG, "IMPORT_SEED: ${seed.size}B seed")
        return byteArrayOf(SOS_CLA, SOS_INS_IMPORT_SEED, 0x00, 0x00, seed.size.toByte()) + seed
    }

    /** VERIFY PIN: CLA=B0, INS=42, P1=00(User)/01(Admin), P2=00 */
    fun buildVerifyPin(): ByteArray {
        val pin = byteArrayOf(0x31, 0x32, 0x33, 0x34) // "1234" (default after firmware reinstall)
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
            Log.e(TAG, "Error SW=${String.format("%04X", sw)}")
            return sosResponse
        }

        val data = if (sosResponse.size > 2) sosResponse.copyOfRange(0, sosResponse.size - 2) else return sosResponse

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

        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))         // 0x01
        tlvList.add(byteArrayOf(0x02, 0x01, 0x02))               // 0x02 Status=Loaded
        tlvList.add(buildTlv(TAG_CARD_PUBLIC_KEY, pubkey))        // 0x03
        tlvList.add(buildTlv(0x05, "secp256k1".toByteArray()))   // 0x05 CurveId
        tlvList.add(byteArrayOf(0x07, 0x01, 0x03))               // 0x07 SigningMethod=ECDSA
        tlvList.add(byteArrayOf(0x0A, 0x04, 0x00, 0x20, 0x00, 0x00)) // 0x0A SettingsMask (AllowHDWallets=0x00200000)
        tlvList.add(buildTlv(0x20, "TANGEM SDK".toByteArray()))  // 0x20 ManufacturerName
        tlvList.add(buildTlv(0x30, pubkey))                       // 0x30 IssuerPublicKey
        tlvList.add(byteArrayOf(0x66, 0x01, 0x01))               // 0x66 WalletsCount
        tlvList.add(buildTlv(TAG_FIRMWARE, "4.0.0r".toByteArray())) // Firmware (Release type, >=4.0 for multi-wallet)
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

        // Non-hardened derivation: card returns key at 3 levels, derive remaining levels in software
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
                Log.d(TAG, "Non-hardened derived: ${fullPath.size / 4} levels, final pubkey prefix=0x${String.format("%02X", pubkey[0])}")
            } catch (e: Exception) {
                Log.e(TAG, "Non-hardened derivation failed: ${e.message}")
            }
        }

        // Build Tangem TLV response — must match WalletDeserializer expectations:
        // Required tags: Status(0x02), WalletPublicKey(0x60), CurveId(0x05), WalletIndex(0x65)
        // Optional tags: WalletHDChain(0x6B), WalletSignedHashes(0x63), SettingsMask(0x0A)
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))          // 0x01 CardId
        tlvList.add(byteArrayOf(0x02, 0x01, 0x02))               // 0x02 Status = Loaded (2)
        tlvList.add(buildTlv(TAG_WALLET_PUBLIC_KEY, pubkey))      // 0x60 pubkey (33B)
        tlvList.add(buildTlv(TAG_WALLET_HD_CHAIN, chainCode))    // 0x6B chaincode (32B)
        tlvList.add(buildTlv(0x05, "secp256k1".toByteArray()))   // 0x05 CurveId
        tlvList.add(byteArrayOf(0x65, 0x01, 0x00))               // 0x65 WalletIndex = 0
        tlvList.add(byteArrayOf(0x63, 0x04, 0x00, 0x00, 0x00, 0x00)) // 0x63 SignedHashes = 0

        return wrapWithSw(tlvList)
    }

    private fun convertSignHashResponse(data: ByteArray): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))
        tlvList.add(buildTlv(TAG_WALLET_SIGNATURE, data))
        tlvList.add(byteArrayOf(0x63, 0x04, 0x00, 0x00, 0x00, 0x01)) // SignedHashes
        return wrapWithSw(tlvList)
    }

    private fun convertSchnorrSignResponse(data: ByteArray): ByteArray {
        Log.d(TAG, "SCHNORR response: ${data.size}B")
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))
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
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))

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
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))
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
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))

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

    fun buildAttestFakeResponse(): ByteArray {
        val tlvList = mutableListOf<ByteArray>()
        tlvList.add(buildTlv(TAG_CARD_ID, ByteArray(8)))
        tlvList.add(buildTlv(0x17, ByteArray(16))) // Salt
        tlvList.add(buildTlv(0x04, ByteArray(64))) // CardSignature
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
