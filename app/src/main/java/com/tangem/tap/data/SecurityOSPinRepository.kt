package com.tangem.tap.data

import android.util.Log

/**
 * In-memory PIN storage for SecurityOS card communication.
 * PIN is held only during NFC session — never persisted to disk.
 * Populated by SecurityOSSessionViewDelegate (PIN dialog), consumed by SecurityOSCardReader.
 */
object SecurityOSPinRepository {

    private const val TAG = "SecurityOSPinRepo"
    private const val DEFAULT_PIN = "1234"

    @Volatile
    private var sessionPin: ByteArray? = null

    /**
     * Set the PIN for the current NFC session.
     * Called by SecurityOSSessionViewDelegate after user enters PIN.
     */
    fun setPin(pin: ByteArray) {
        sessionPin = pin
        Log.d(TAG, "PIN set for session (${pin.size} bytes)")
    }

    /**
     * Get the current session PIN, or default "1234" if not set.
     * Called by SecurityOSCardReader before each transceive.
     */
    fun getPin(): ByteArray {
        return sessionPin ?: DEFAULT_PIN.toByteArray()
    }

    /**
     * Clear PIN from memory. Called when NFC session ends or app goes to background.
     */
    fun clearPin() {
        sessionPin = null
        Log.d(TAG, "PIN cleared from memory")
    }

    /**
     * Check if user has explicitly set a PIN for this session.
     */
    fun hasSessionPin(): Boolean = sessionPin != null
}
