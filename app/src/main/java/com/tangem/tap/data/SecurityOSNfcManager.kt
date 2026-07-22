package com.tangem.tap.data

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import com.tangem.common.extensions.VoidCallback
import com.tangem.common.nfc.ReadingActiveListener
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class SecurityOSNfcManager : NfcAdapter.ReaderCallback, ReadingActiveListener, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "SecurityOSNfcManager"
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        const val IGNORE_DEBOUNCE_MS = 1_500
        private const val DELAY_BEFORE_ENABLE = 300L
    }

    override var readingIsActive: Boolean = false
        set(value) {
            Log.d(TAG, "set readingIsActive $value")
            field = value
            if (value && pendingTag != null) {
                val tag = pendingTag
                pendingTag = null
                Log.d(TAG, "Session started with pending tag - auto-connecting")
                reader.onTagDiscovered(tag)
            }
        }

    val reader = SecurityOSCardReader()

    val isNfcEnabled: Boolean
        get() = nfcAdapter?.isEnabled == true

    private val onTagDiscoveredListeners: MutableList<VoidCallback> = mutableListOf()
    private var activity: Activity? = null
    var nfcAdapter: NfcAdapter? = null
        private set
    private var isReaderModeEnabled: Boolean = false

    // Pending tag: card detected before session started
    var pendingTag: Tag? = null
        private set
    var onCardDetectedBeforeSession: ((Tag) -> Unit)? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED &&
                intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_ON) == NfcAdapter.STATE_ON
            ) {
                Log.d(TAG, "NFC adapter turned ON, enabling reader mode")
                enableReaderMode()
            }
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.d(TAG, "NFC tag discovered, readingIsActive=$readingIsActive")
        onTagDiscoveredListeners.forEach { it.invoke() }
        if (readingIsActive) {
            reader.onTagDiscovered(tag)
        } else if (tag != null) {
            // Original Tangem SDK: ignore tag to prevent No support application dialog
            ignoreTag(tag)
        }
    }

    fun addTagDiscoveredListener(listener: VoidCallback) {
        onTagDiscoveredListeners.add(listener)
    }

    fun removeTagDiscoveredListener(listener: VoidCallback) {
        onTagDiscoveredListeners.remove(listener)
    }

    override fun onForceEnableReadingMode() {
        Log.d(TAG, "onForceEnableReadingMode")
        enableReaderModeIfNfcEnabled()
    }

    override fun onForceDisableReadingMode() {
        Log.d(TAG, "onForceDisableReadingMode")
        disableReaderMode()
    }

    fun setCurrentActivity(activity: Activity) {
        this.activity = activity
        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
        reader.listener = this
        Log.d(TAG, "setCurrentActivity, nfcAdapter=${nfcAdapter != null}")
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        activity?.registerReceiver(broadcastReceiver, filter)
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "onStart")
        reader.listener = this
        enableReaderModeIfNfcEnabled()
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "onStop")
        // Stop session but keep reader mode active
        // Reader mode stays active so NFC tags are still caught by the system
        // and forwarded to onTagDiscovered. This prevents "No support application" dialog.
        reader.stopSession(true)
        reader.listener = null
        pendingTag = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        activity?.unregisterReceiver(broadcastReceiver)
        activity = null
        nfcAdapter = null
    }

    fun enableReaderModeIfNfcEnabled() {
        if (nfcAdapter?.isEnabled == true) {
            enableReaderMode()
        } else {
            Log.w(TAG, "NFC is not enabled")
        }
    }

    fun enableReaderMode() {
        Log.d(TAG, "enableReaderMode isReaderModeEnabled=$isReaderModeEnabled")
        if (activity?.isDestroyed == false) {
            if (isReaderModeEnabled) {
                disableReaderMode()
                Thread.sleep(DELAY_BEFORE_ENABLE)
            }
            nfcAdapter?.enableReaderMode(activity, this, READER_FLAGS, Bundle())
            isReaderModeEnabled = true
            Log.d(TAG, "reader mode enabled")
        }
    }

    private fun disableReaderMode() {
        if (activity?.isDestroyed == false && isReaderModeEnabled) {
            nfcAdapter?.disableReaderMode(activity)
            isReaderModeEnabled = false
        }
    }

    private fun ignoreTag(tag: Tag?) {
        try {
            nfcAdapter?.ignore(tag, IGNORE_DEBOUNCE_MS, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "ignoreTag failed", e)
        }
        IsoDep.get(tag)?.let {
            try { it.close() } catch (_: Exception) {}
        }
    }
}