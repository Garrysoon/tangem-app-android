package com.tangem.tap.data

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import com.tangem.common.CompletionResult
import com.tangem.common.apdu.CommandApdu
import com.tangem.common.apdu.ResponseApdu
import com.tangem.common.core.CompletionCallback
import com.tangem.common.core.TagType
import com.tangem.common.core.TangemSdkError
import com.tangem.common.nfc.CardReader
import com.tangem.common.nfc.ReadingActiveListener
import com.tangem.sdk.nfc.NfcTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Result of PIN verification — maps to specific TangemSdkError instead of generic TagLost.
 */
private sealed class PinVerifyResult {
    data class Success(val remaining: Int) : PinVerifyResult()
    data class WrongPin(val remaining: Int) : PinVerifyResult()
    object Blocked : PinVerifyResult()
    object TagLost : PinVerifyResult()
    data class Error(val message: String) : PinVerifyResult()
}

class SecurityOSCardReader : CardReader {

    companion object {
        private const val TAG = "SecurityOSCardReader"
        private const val ISO_DEP_TIMEOUT_MS = 240_000
        private const val CONNECTION_DELAY = 100L
        private val TANGEM_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x08, 0x12, 0x01, 0x02, 0x08)
        private val SECURITYOS_AID = byteArrayOf(0x53, 0x61, 0x74, 0x6F, 0x43, 0x68, 0x69, 0x70)
    }

    override val tag: MutableSharedFlow<TagType?> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override var scope: CoroutineScope? = null

    var listener: ReadingActiveListener? = null

    private val readerMutex = Mutex()
    private var nfcTag: NfcTag? = null
        set(value) {
            field = value
            Log.d(TAG, "received tag: ${value?.type?.name?.uppercase()}")
            scope?.launchWithLock(readerMutex) { tag.tryEmit(value?.type) }
        }

    override fun startSession() {
        Log.d(TAG, "start NFC session, scope=${scope != null}")
        tag.resetReplayCache()
        listener?.readingIsActive = true
        listener?.onForceEnableReadingMode()
        scope?.launchWithLock(readerMutex) {
            Log.d(TAG, "start NFC session coroutine")
        }
    }

    override fun pauseSession() {
        Log.d(TAG, "pause NFC session")
        listener?.readingIsActive = false
    }

    override fun resumeSession() {
        Log.d(TAG, "resume NFC session")
        listener?.readingIsActive = true
    }

    override fun stopSession(cancelled: Boolean) {
        Log.d(TAG, "stop NFC session, cancelled=$cancelled")
        listener?.readingIsActive = false
    }

    private var pendingSelectAfterConnect = false

    fun onTagDiscovered(tag: Tag?) {
        scope?.launchWithLock(readerMutex) {
            IsoDep.get(tag)?.let { isoDep ->
                connect(
                    isoDep = isoDep,
                    onSuccess = {
                        Log.d(TAG, "tag connected, IsoDep ready")
                        nfcTag = NfcTag(TagType.Nfc, isoDep)
                        // Send SELECT AID now that IsoDep is connected
                        sendSelectAid(isoDep)
                    },
                ) {
                    Log.e(TAG, "tag connect failed")
                }
            } ?: Log.w(TAG, "not an IsoDep tag")
        }
    }

    private fun sendSelectAid(isoDep: IsoDep) {
        try {
            val selectApdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x08) + SECURITYOS_AID
            Log.d(TAG, "sending SELECT AID: ${selectApdu.joinToString("") { String.format("%02X", it) }}")
            val response = isoDep.transceive(selectApdu)
            if (response != null && response.size >= 2) {
                val sw = (response[response.size - 2].toInt() and 0xFF) shl 8 or
                    (response[response.size - 1].toInt() and 0xFF)
                Log.d(TAG, "SELECT AID response: SW=${String.format("%04X", sw)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SELECT AID failed: ${e.message}")
        }
    }

    private suspend fun connect(isoDep: IsoDep, onSuccess: (IsoDep) -> Unit, onError: () -> Unit) {
        Log.d(TAG, "connecting to IsoDep...")
        try {
            if (isoDep.isConnected) {
                isoDep.close()
                delay(CONNECTION_DELAY)
            }
            isoDep.connect()
            isoDep.timeout = ISO_DEP_TIMEOUT_MS
            Log.d(TAG, "connected, timeout=${isoDep.timeout}")
            onSuccess(isoDep)
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            onError()
        }
    }

    override suspend fun transceiveApdu(apdu: CommandApdu): CompletionResult<ResponseApdu> =
        suspendCancellableCoroutine { continuation ->
            transceiveRaw(apdu.apduData) { result ->
                when (result) {
                    is CompletionResult.Success -> {
                        result.data?.let {
                            val rApdu = ResponseApdu(it)
                            Log.d(TAG, "APDU response: SW=${String.format("%04X", rApdu.sw)}")
                            continuation.resume(CompletionResult.Success(rApdu))
                        }
                    }
                    is CompletionResult.Failure -> continuation.resume(CompletionResult.Failure(result.error))
                }
            }
        }

    override suspend fun transceiveRaw(apduData: ByteArray): CompletionResult<ByteArray?> =
        suspendCancellableCoroutine { continuation ->
            transceiveRaw(apduData) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun replaceAidIfNeeded(apduData: ByteArray): ByteArray {
        if (apduData.size >= 13 &&
            apduData[0] == 0x00.toByte() && apduData[1] == 0xA4.toByte()
        ) {
            val aidStart = 5
            val aid = apduData.copyOfRange(aidStart, aidStart + 8)
            if (aid.contentEquals(TANGEM_AID)) {
                Log.d(TAG, "REPLACING Tangem AID -> SecurityOS AID")
                val modified = apduData.copyOf()
                SECURITYOS_AID.copyInto(modified, aidStart)
                return modified
            }
        }
        return apduData
    }

    private fun transceiveRaw(apduData: ByteArray, callback: CompletionCallback<ByteArray?>) {
        val aidReplaced = replaceAidIfNeeded(apduData)
        val mapped = SecurityOSCommandMapper.interceptCommand(aidReplaced)

        val dataToSend: ByteArray
        val commandType: SecurityOSCommandMapper.CommandType
        var needsPin = false

        if (mapped != null) {
            dataToSend = mapped
            val ins = aidReplaced[1].toInt() and 0xFF
            commandType = when (ins) {
                0xF2 -> SecurityOSCommandMapper.lastCommandType // mapper already parsed InteractionMode from TLV
                0xFB -> SecurityOSCommandMapper.lastCommandType // set by mapper (SIGN_HASH or SCHNORR_SIGN)
                0xF3, 0xF4, 0xF5 -> SecurityOSCommandMapper.CommandType.GET_AUTHENTIKEY
                0xF8 -> SecurityOSCommandMapper.CommandType.IMPORT_SEED
                else -> SecurityOSCommandMapper.CommandType.UNKNOWN
            }
            SecurityOSCommandMapper.lastCommandType = commandType
            Log.d(TAG, "MAPPED command → ${commandType.name}: ${dataToSend.joinToString("") { String.format("%02X", it) }}")

            needsPin = commandType in setOf(
                SecurityOSCommandMapper.CommandType.GET_XPUB,
                SecurityOSCommandMapper.CommandType.SIGN_HASH,
                SecurityOSCommandMapper.CommandType.SCHNORR_SIGN,
                SecurityOSCommandMapper.CommandType.IMPORT_SEED,
            )

            // Commands that need fake response WITHOUT hitting the card
            val fakeResponse = when (ins) {
                0xF3, 0xF4, 0xF5 -> SecurityOSCommandMapper.buildAttestFakeResponse()
                0xE0, 0xE1, 0xF6, 0xF7, 0xD0, 0xD1 -> SecurityOSCommandMapper.buildFakeSuccessResponse()
                else -> null
            }
            if (fakeResponse != null) {
                Log.d(TAG, "Fake response for INS=${String.format("%02X", ins)}: ${fakeResponse.size}B")
                callback(CompletionResult.Success(fakeResponse))
                return
            }
        } else {
            dataToSend = aidReplaced
            // Detect SecurityOS CLA=0xB0 commands for command type tracking
            val insByte = aidReplaced[1]
            commandType = when (insByte) {
                0x7E.toByte() -> SecurityOSCommandMapper.CommandType.MUSIG2_NONCE
                0x7F.toByte() -> SecurityOSCommandMapper.CommandType.MUSIG2_PARTIAL_SIGN
                0xA0.toByte() -> SecurityOSCommandMapper.CommandType.MUSIG2_AGGREGATE_PUB
                0x7C.toByte() -> SecurityOSCommandMapper.CommandType.TAPROOT_TWEAK
                0x7D.toByte() -> SecurityOSCommandMapper.CommandType.SP_IMPORT_KEYS
                0xA4.toByte() -> SecurityOSCommandMapper.CommandType.SP_TWEAK
                0xA5.toByte() -> SecurityOSCommandMapper.CommandType.SP_SIGN
                0x6F.toByte() -> SecurityOSCommandMapper.CommandType.SIGN_HASH // PSBT sign_tx
                else -> SecurityOSCommandMapper.CommandType.UNKNOWN
            }
            if (commandType != SecurityOSCommandMapper.CommandType.UNKNOWN) {
                SecurityOSCommandMapper.lastCommandType = commandType
            }
            val ins = String.format("%02X", insByte)
            Log.d(TAG, "passthrough CLA=${String.format("%02X", aidReplaced[0])} INS=$ins: ${dataToSend.joinToString("") { String.format("%02X", it) }}")
        }

        // Verify PIN immediately before transceive — no other code between
        if (needsPin) {
            when (val pinResult = verifyPin()) {
                is PinVerifyResult.Success -> { /* OK, continue */ }
                is PinVerifyResult.WrongPin -> {
                    Log.e(TAG, "Wrong PIN — ${pinResult.remaining} attempts left")
                    callback(CompletionResult.Failure(TangemSdkError.WrongAccessCode()))
                    return
                }
                is PinVerifyResult.Blocked -> {
                    Log.e(TAG, "Card blocked — PUK required")
                    callback(CompletionResult.Failure(TangemSdkError.CardVerificationFailed()))
                    return
                }
                is PinVerifyResult.TagLost -> {
                    Log.e(TAG, "Tag lost during PIN verify")
                    callback(CompletionResult.Failure(TangemSdkError.TagLost()))
                    return
                }
                is PinVerifyResult.Error -> {
                    Log.e(TAG, "PIN verify error: ${pinResult.message}")
                    callback(CompletionResult.Failure(TangemSdkError.ErrorProcessingCommand()))
                    return
                }
            }
        }

        // Send pre-command ONLY for GET_XPUB (not for SELECT, GET_STATUS, etc.)
        if (commandType == SecurityOSCommandMapper.CommandType.GET_XPUB) {
            val preCmd = SecurityOSCommandMapper.pendingPreCommand
            if (preCmd != null) {
                SecurityOSCommandMapper.pendingPreCommand = null
                try {
                    Log.d(TAG, "pre-command: ${preCmd.joinToString("") { String.format("%02X", it) }}")
                    nfcTag?.isoDep?.transceive(preCmd)
                } catch (e: Exception) {
                    Log.e(TAG, "pre-command failed: ${e.message}")
                }
            }
        }

        val rawResponse: ByteArray? = try {
            val response = nfcTag?.isoDep?.transceive(dataToSend)
            Log.d(TAG, "response: ${response?.joinToString("") { String.format("%02X", it) }}")
            // Send post-command ONLY after GET_XPUB
            if (commandType == SecurityOSCommandMapper.CommandType.GET_XPUB) {
                val postCmd = SecurityOSCommandMapper.pendingPostCommand
                if (postCmd != null) {
                    SecurityOSCommandMapper.pendingPostCommand = null
                    SecurityOSCommandMapper.isTlvMode = false
                    try {
                        Log.d(TAG, "post-command: ${postCmd.joinToString("") { String.format("%02X", it) }}")
                        nfcTag?.isoDep?.transceive(postCmd)
                    } catch (e: Exception) {
                        Log.e(TAG, "post-command failed: ${e.message}")
                    }
                }
            }
            response
        } catch (exception: TagLostException) {
            Log.e(TAG, "TagLostException")
            callback(CompletionResult.Failure(TangemSdkError.TagLost()))
            nfcTag = null
            return
        } catch (exception: Exception) {
            Log.e(TAG, "transceive exception: ${exception.message}", exception)
            callback(CompletionResult.Failure(TangemSdkError.ErrorProcessingCommand()))
            nfcTag = null
            return
        }

        if (mapped != null && commandType != SecurityOSCommandMapper.CommandType.UNKNOWN) {
            val mappedResponse = SecurityOSCommandMapper.mapResponse(commandType, rawResponse)
            Log.d(TAG, "mapped response: ${mappedResponse?.joinToString("") { String.format("%02X", it) }}")
            callback(CompletionResult.Success(mappedResponse))
        } else {
            callback(CompletionResult.Success(rawResponse))
        }
    }

    // Rate limiting: minimum 5 seconds between PIN attempts
    private var lastPinAttemptTime: Long = 0
    private val PIN_MIN_INTERVAL_MS = 5000L
    private val PIN_MAX_TRIES = 3

    // Remaining PIN attempts tracking (0 = blocked, 1..3 = attempts left)
    var lastPinRemainingAttempts: Int = PIN_MAX_TRIES
        private set
    var isPinBlocked: Boolean = false
        private set

    private fun verifyPin(): PinVerifyResult {
        try {
            // Rate limiting — prevent rapid brute-force
            val now = System.currentTimeMillis()
            val elapsed = now - lastPinAttemptTime
            if (elapsed < PIN_MIN_INTERVAL_MS) {
                val waitMs = PIN_MIN_INTERVAL_MS - elapsed
                Log.w(TAG, "PIN rate limit: waiting ${waitMs}ms before next attempt")
                Thread.sleep(waitMs)
            }
            lastPinAttemptTime = System.currentTimeMillis()

            val pin = byteArrayOf(0x31, 0x32, 0x33, 0x34) // "1234" (default after firmware reinstall)
            val verifyApdu = byteArrayOf(0xB0.toByte(), 0x42, 0x00, 0x00, pin.size.toByte()) + pin
            val response = nfcTag?.isoDep?.transceive(verifyApdu)
            val sw = if (response != null && response.size >= 2) {
                (response[response.size - 2].toInt() and 0xFF) shl 8 or (response[response.size - 1].toInt() and 0xFF)
            } else 0

            when (sw) {
                0x9000 -> {
                    Log.d(TAG, "verify PIN: SW=9000 OK")
                    lastPinRemainingAttempts = PIN_MAX_TRIES
                    isPinBlocked = false
                    return PinVerifyResult.Success(PIN_MAX_TRIES)
                }
                in 0x6300..0x630F -> {
                    val remaining = sw and 0x0F
                    lastPinRemainingAttempts = remaining
                    isPinBlocked = false
                    Log.e(TAG, "verify PIN: WRONG PIN! Remaining attempts: $remaining/$PIN_MAX_TRIES")
                    if (remaining == 1) {
                        Log.e(TAG, "WARNING: Only 1 attempt left! Card will be LOCKED on next failure!")
                    }
                    return PinVerifyResult.WrongPin(remaining)
                }
                0x9C0C -> {
                    lastPinRemainingAttempts = 0
                    isPinBlocked = true
                    Log.e(TAG, "verify PIN: CARD BLOCKED (SW=9C0C) — PUK required to reset!")
                    return PinVerifyResult.Blocked
                }
                else -> {
                    Log.e(TAG, "verify PIN: unexpected SW=${String.format("%04X", sw)}")
                    return PinVerifyResult.Error("Unexpected SW: ${String.format("%04X", sw)}")
                }
            }
        } catch (e: TagLostException) {
            Log.e(TAG, "TagLost during PIN verify")
            nfcTag = null
            listener?.readingIsActive = false
            return PinVerifyResult.TagLost
        } catch (e: Exception) {
            Log.e(TAG, "verify PIN failed: ${e.message}")
            return PinVerifyResult.Error(e.message ?: "Unknown error")
        }
    }

    override fun readSlixTag(callback: CompletionCallback<ResponseApdu>) {
        callback(CompletionResult.Failure(TangemSdkError.ErrorProcessingCommand()))
    }

    override fun forceEnableReaderMode() {
        listener?.onForceEnableReadingMode()
    }

    override fun forceDisableReaderMode() {
        listener?.onForceDisableReadingMode()
    }

    private fun CoroutineScope.launchWithLock(mutex: Mutex, action: suspend () -> Unit) {
        this.launch {
            mutex.withLock(null) {
                action()
            }
        }
    }
}
