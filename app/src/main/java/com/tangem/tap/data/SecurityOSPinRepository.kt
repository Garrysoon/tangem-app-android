package com.tangem.tap.data

import android.util.Log

/**
 * In-memory PIN storage for SecurityOS card communication.
 * Stores both User PIN and Admin PIN separately.
 * PINs are held only during NFC session — never persisted to disk.
 */
object SecurityOSPinRepository {

    private const val TAG = "SecurityOSPinRepo"
    private const val DEFAULT_USER_PIN = "1234"
    private const val DEFAULT_ADMIN_PIN = "12345678"

    @Volatile
    private var userPin: ByteArray? = null

    @Volatile
    private var adminPin: ByteArray? = null

    fun setUserPin(pin: ByteArray) {
        userPin = pin
        Log.d(TAG, "User PIN set (${pin.size} bytes)")
    }

    fun setAdminPin(pin: ByteArray) {
        adminPin = pin
        Log.d(TAG, "Admin PIN set (${pin.size} bytes)")
    }

    fun getUserPin(): ByteArray {
        return userPin ?: DEFAULT_USER_PIN.toByteArray()
    }

    fun getAdminPin(): ByteArray {
        return adminPin ?: DEFAULT_ADMIN_PIN.toByteArray()
    }

    fun hasAdminPin(): Boolean = adminPin != null

    fun clearAll() {
        userPin = null
        adminPin = null
        Log.d(TAG, "All PINs cleared from memory")
    }
}
