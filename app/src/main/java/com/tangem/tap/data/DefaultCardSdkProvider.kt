package com.tangem.tap.data

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tangem.TangemSdk
import com.tangem.common.CardFilter
import com.tangem.common.authentication.AuthenticationManager
import com.tangem.common.card.FirmwareVersion
import com.tangem.common.core.Config
import com.tangem.common.services.secure.SecureStorage
import com.tangem.core.analytics.api.AnalyticsExceptionHandler
import com.tangem.core.analytics.models.ExceptionAnalyticsEvent
import com.tangem.crypto.bip39.Wordlist
import com.tangem.data.card.sdk.CardSdkOwner
import com.tangem.data.card.sdk.CardSdkProvider
import com.tangem.datasource.api.common.AuthProvider
import com.tangem.datasource.api.common.config.ApiConfig
import com.tangem.datasource.api.common.config.ApiEnvironmentConfig
import com.tangem.datasource.api.common.config.managers.ApiConfigsManager
import com.tangem.datasource.api.common.config.managers.MutableApiConfigsManager
import com.tangem.datasource.utils.AddHeadersInterceptor
import com.tangem.datasource.utils.RequestHeader
import com.tangem.operations.attestation.api.TangemApiServiceSettings
import com.tangem.sdk.extensions.getWordlist
import com.tangem.sdk.extensions.initAuthenticationManager
import com.tangem.sdk.extensions.initKeystoreManager
import com.tangem.sdk.extensions.unsubscribe
import com.tangem.sdk.nfc.AndroidNfcAvailabilityProvider
import com.tangem.sdk.storage.create
import com.tangem.tap.foregroundActivityObserver
import com.tangem.utils.Provider
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.info.AppInfoProvider
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
internal class DefaultCardSdkProvider @Inject constructor(
    private val analyticsExceptionHandler: AnalyticsExceptionHandler,
    private val dispatchers: CoroutineDispatcherProvider,
    private val apiConfigsManager: ApiConfigsManager,
    appInfoProvider: AppInfoProvider,
    authProvider: AuthProvider,
) : CardSdkProvider, CardSdkOwner {

    private val observer = Observer()
    private var holder: Holder? = null
    var nfcManager: SecurityOSNfcManager? = null
        private set

    override val sdk: TangemSdk
        get() = holder?.sdk ?: tryToRegisterWithForegroundActivity()

    init {
        val mutableManager = apiConfigsManager as? MutableApiConfigsManager
        mutableManager?.addListener(
            object : MutableApiConfigsManager.ApiConfigEnvChangeListener(id = ApiConfig.ID.TangemTech) {
                override fun onChange(environmentConfig: ApiEnvironmentConfig) {
                    holder?.sdk?.config?.tangemApiBaseUrl = environmentConfig.baseUrl
                }
            },
        )
        val apiEnvironment = Provider {
            apiConfigsManager.getEnvironmentConfig(ApiConfig.ID.TangemTech).environment
        }
        val platformHeaders = RequestHeader.AppVersionPlatformHeaders(appInfoProvider)
        val apiKeyHeader = RequestHeader.TangemApiKeyHeader(authProvider, apiEnvironment)
        TangemApiServiceSettings.addInterceptors(
            AddHeadersInterceptor(platformHeaders.values + apiKeyHeader.values),
        )
    }

    override fun register(activity: FragmentActivity) = runBlocking(dispatchers.mainImmediate) {
        if (activity.isDestroyed || activity.isFinishing || activity.isChangingConfigurations) {
            val message = "Tangem SDK owner registration skipped: activity is destroyed or finishing"
            analyticsExceptionHandler.sendException(
                ExceptionAnalyticsEvent(
                    exception = IllegalStateException(message),
                    params = errorParams,
                ),
            )
            Log.i(TAG, message)
            return@runBlocking
        }
        if (holder != null) unsubscribeAndCleanup()
        initialize(activity)
        activity.lifecycle.addObserver(observer)
        Log.i(TAG, "Tangem SDK owner registered with SecurityOS")
    }

    private fun tryToRegisterWithForegroundActivity(): TangemSdk = runBlocking(dispatchers.mainImmediate) {
        val warning = "Tangem SDK holder is null, trying to recreate it with foreground activity"
        Log.w(TAG, warning)
        val activity = foregroundActivityObserver.foregroundActivity
            ?: error("Tangem SDK holder is null and foreground activity is null")
        register(activity)
        return@runBlocking holder?.sdk ?: error("Tangem SDK is null after re-registering")
    }

    private fun initialize(activity: FragmentActivity) {
        val secureStorage = SecureStorage.create(activity)
        val authenticationManager = TangemSdk.initAuthenticationManager(activity)
        val keystoreManager = TangemSdk.initKeystoreManager(authenticationManager, secureStorage)

        val securityOSNfcManager = SecurityOSNfcManager().apply {
            setCurrentActivity(activity)
            activity.lifecycle.addObserver(this)
        }

        // Explicitly enable reader mode — lifecycle observer may miss onStart if Activity already started
        securityOSNfcManager.enableReaderModeIfNfcEnabled()

        val viewDelegate = SecurityOSSessionViewDelegate()

        val androidNfcAvailabilityProvider = AndroidNfcAvailabilityProvider(activity)
        val sdk = TangemSdk(
            reader = securityOSNfcManager.reader,
            viewDelegate = viewDelegate,
            nfcAvailabilityProvider = androidNfcAvailabilityProvider,
            secureStorage = secureStorage,
            authenticationManager = authenticationManager,
            keystoreManager = keystoreManager,
            wordlist = Wordlist.getWordlist(),
            config = config.apply {
                val apiConfig = apiConfigsManager.getEnvironmentConfig(id = ApiConfig.ID.TangemTech)
                tangemApiBaseUrl = apiConfig.baseUrl
            },
        )

        holder = Holder(
            activity = activity,
            securityOSNfcManager = securityOSNfcManager,
            authenticationManager = authenticationManager,
            sdk = sdk,
        )

        Log.i(TAG, "Tangem SDK initialized with SecurityOS NFC reader")
    }

    private fun unsubscribeAndCleanup() {
        val currentHolder = holder
        if (currentHolder == null) {
            Log.i(TAG, "Tangem SDK already unsubscribed and cleaned up")
            return
        }
        with(currentHolder) {
            securityOSNfcManager.onStop(activity)
            authenticationManager.unsubscribe(activity)
            activity.lifecycle.removeObserver(observer)
        }
        holder = null
        Log.i(TAG, "Tangem SDK unsubscribed and cleaned up")
    }

    inner class Observer : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            Log.i(TAG, "Tangem SDK owner destroyed")
            unsubscribeAndCleanup()
        }
    }

    data class Holder(
        val activity: FragmentActivity,
        val sdk: TangemSdk,
        val securityOSNfcManager: SecurityOSNfcManager,
        val authenticationManager: AuthenticationManager,
    )

    private companion object {
        private const val TAG = "DefaultCardSdkProvider"

        val config = Config(
            linkedTerminal = true,
            filter = CardFilter(
                allowedCardTypes = FirmwareVersion.FirmwareType.entries.toList(),
                maxFirmwareVersion = FirmwareVersion(major = 6, minor = 33),
                batchIdFilter = CardFilter.Companion.ItemFilter.Deny(
                    items = setOf("0027", "0030", "0031", "0035"),
                ),
            ),
        )

        val errorParams = mapOf(
            "Category" to "Tangem SDK",
            "Event" to "Warning",
        )
    }
}
