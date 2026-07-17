package com.tangem.tap.data

import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tangem.Message
import com.tangem.SessionViewDelegate
import com.tangem.ViewDelegateMessage
import com.tangem.WrongValueType
import com.tangem.common.StringsLocator
import com.tangem.common.UserCodeType
import com.tangem.common.core.CompletionCallback
import com.tangem.common.CompletionResult
import com.tangem.common.core.Config
import com.tangem.common.core.ProductType
import com.tangem.common.core.TangemError
import com.tangem.common.core.TangemSdkError
import com.tangem.common.extensions.VoidCallback
import com.tangem.operations.resetcode.ResetCodesViewDelegate
import com.tangem.operations.resetcode.ResetCodesViewState
import com.tangem.tap.ForegroundActivityObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SecurityOSSessionViewDelegate : SessionViewDelegate {

    companion object {
        private const val TAG = "SecurityOSViewDelegate"
    }

    override val viewVisibility: StateFlow<Boolean> = MutableStateFlow(false)

    override val resetCodesViewDelegate: ResetCodesViewDelegate = object : ResetCodesViewDelegate {
        override var stopSessionCallback: VoidCallback = {}
        override val stringsLocator: StringsLocator = object : StringsLocator {
            override fun getString(stringId: StringsLocator.ID, vararg formatArgs: Any, defaultValue: String): String = defaultValue
        }
        override fun setState(state: ResetCodesViewState) { Log.d(TAG, "ResetCodes setState: $state") }
        override fun hide(callback: VoidCallback) { callback() }
        override fun showError(error: TangemError) { Log.e(TAG, "ResetCodes error: ${error.customMessage}") }
        override fun showAlert(title: String, message: String, onContinue: VoidCallback) { onContinue() }
    }

    override suspend fun onSessionStarted(
        cardId: String?,
        message: ViewDelegateMessage?,
        enableHowTo: Boolean,
        iconScanRes: Int?,
        productType: ProductType,
    ) {
        Log.d(TAG, "Session started, cardId=$cardId")
    }

    override fun onSecurityDelay(ms: Int, totalDurationSeconds: Int, productType: ProductType) {
        Log.d(TAG, "Security delay: ${ms}ms / ${totalDurationSeconds}s")
    }

    override fun onDelay(total: Int, current: Int, step: Int, productType: ProductType) {
        Log.d(TAG, "Delay: step=$step, current=$current/$total")
    }

    override fun onTagLost(productType: ProductType) {
        Log.d(TAG, "Tag lost")
    }

    override fun onTagConnected() {
        Log.d(TAG, "Tag connected")
    }

    override fun onWrongCard(wrongValueType: WrongValueType) {
        Log.d(TAG, "Wrong card: $wrongValueType")
    }

    override fun onSessionStopped(message: Message?, onDialogHidden: () -> Unit) {
        Log.d(TAG, "Session stopped")
        onDialogHidden()
    }

    override fun onError(error: TangemError) {
        Log.e(TAG, "Error: ${error.customMessage}")
    }

    override fun requestUserCode(
        type: UserCodeType,
        isFirstAttempt: Boolean,
        showForgotButton: Boolean,
        cardId: String?,
        callback: CompletionCallback<String>,
    ) {
        Log.d(TAG, "Request user code: $type, isFirstAttempt=$isFirstAttempt, cardId=$cardId")

        val dialogTitle = when (type) {
            UserCodeType.AccessCode -> "Enter User PIN"
            UserCodeType.Passcode -> "Enter Admin PIN"
        }
        val hint = when (type) {
            UserCodeType.AccessCode -> "User PIN"
            UserCodeType.Passcode -> "Admin PIN"
        }

        val activity = ForegroundActivityObserver.foregroundActivity
        if (activity == null || activity.isDestroyed || activity.isFinishing) {
            Log.w(TAG, "No foreground activity, using default PIN")
            val pin = when (type) {
                UserCodeType.AccessCode -> SecurityOSPinRepository.getUserPin()
                UserCodeType.Passcode -> SecurityOSPinRepository.getAdminPin()
            }
            callback(CompletionResult.Success(String(pin)))
            return
        }

        activity.runOnUiThread {
            val editText = EditText(activity).apply {
                this.hint = hint
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setSelection(0)
                val dp16 = (16 * activity.resources.displayMetrics.density).toInt()
                setPadding(dp16, dp16, dp16, dp16)
            }

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val dp8 = (8 * activity.resources.displayMetrics.density).toInt()
                setPadding(dp8, 0, dp8, 0)
                addView(editText)
            }

            if (!isFirstAttempt) {
                val errorText = TextView(activity).apply {
                    text = "Wrong PIN. Try again."
                    setTextColor(0xFFFF4444.toInt())
                    val dp16 = (16 * activity.resources.displayMetrics.density).toInt()
                    setPadding(dp16, 0, dp16, dp16)
                }
                container.addView(errorText, 0)
            }

            val dialog = AlertDialog.Builder(activity)
                .setTitle(dialogTitle)
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    val pin = editText.text.toString().trim()
                    if (pin.isNotEmpty()) {
                        when (type) {
                            UserCodeType.AccessCode -> SecurityOSPinRepository.setUserPin(pin.toByteArray())
                            UserCodeType.Passcode -> SecurityOSPinRepository.setAdminPin(pin.toByteArray())
                        }
                        callback(CompletionResult.Success(pin))
                    } else {
                        callback(CompletionResult.Failure(TangemSdkError.InvalidParams()))
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    callback(CompletionResult.Failure(TangemSdkError.UserCancelled()))
                }
                .create()

            dialog.show()
            editText.requestFocus()
        }
    }

    override fun showWelcomeBackWarning(callback: CompletionCallback<Unit>) {
        callback(CompletionResult.Success(Unit))
    }

    override fun requestUserCodeChange(type: UserCodeType, cardId: String?, callback: CompletionCallback<String>) {
        Log.d(TAG, "Request user code change: $type, cardId=$cardId")
        callback(CompletionResult.Success(""))
    }

    override fun setConfig(config: Config) {
        Log.d(TAG, "Config set")
    }

    override fun setMessage(message: ViewDelegateMessage?) {
        Log.d(TAG, "Message set: ${message?.header}")
    }

    override fun dismiss() {
        Log.d(TAG, "Dismiss")
    }

    override fun attestationDidFail(isDevCard: Boolean, positive: VoidCallback, negative: VoidCallback) {
        Log.d(TAG, "Attestation failed, isDev=$isDevCard")
        positive()
    }

    override fun attestationCompletedOffline(positive: VoidCallback, negative: VoidCallback, retry: VoidCallback) {
        Log.d(TAG, "Attestation completed offline")
        positive()
    }

    override fun attestationCompletedWithWarnings(positive: VoidCallback) {
        Log.d(TAG, "Attestation completed with warnings")
        positive()
    }
}
