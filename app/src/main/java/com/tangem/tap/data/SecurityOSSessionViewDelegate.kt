package com.tangem.tap.data

import android.util.Log
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
import com.tangem.common.extensions.VoidCallback
import com.tangem.operations.resetcode.ResetCodesViewDelegate
import com.tangem.operations.resetcode.ResetCodesViewState
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
        Log.d(TAG, "Request user code: $type, cardId=$cardId")
        val pin = SecurityOSPinRepository.getPin()
        callback(CompletionResult.Success(String(pin)))
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
