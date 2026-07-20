package com.tangem.feature.swap.di

import com.squareup.moshi.Moshi
import com.tangem.data.common.currency.ResponseCryptoCurrenciesFactory
import com.tangem.data.common.network.NetworkFactory
import com.tangem.datasource.api.express.TangemExpressApi
import com.tangem.datasource.api.express.models.response.ExpressErrorResponse
import com.tangem.datasource.api.swap.AcrossBridgeApi
import com.tangem.datasource.api.swap.ThorchainApi
import com.tangem.datasource.api.surveysparrow.SurveySparrowApi
import com.tangem.datasource.crypto.DataSignatureVerifier
import com.tangem.datasource.di.NetworkMoshi
import com.tangem.datasource.local.config.environment.EnvironmentConfig
import com.tangem.datasource.local.preferences.AppPreferencesStore
import com.tangem.domain.account.supplier.SingleAccountListSupplier
import com.tangem.domain.exchange.RampStateManager
import com.tangem.domain.walletmanager.WalletManagersFacade
import com.tangem.feature.swap.DefaultSwapFeedbackRepository
import com.tangem.feature.swap.DefaultSwapRepository
import com.tangem.feature.swap.RaksaSwapRepository
import com.tangem.feature.swap.NoOpSwapFeedbackRepository
import com.tangem.feature.swap.DefaultSwapTransactionRepository
import com.tangem.feature.swap.converters.ErrorsDataConverter
import com.tangem.feature.swap.domain.SwapTransactionRepository
import com.tangem.feature.swap.domain.api.SwapFeedbackRepository
import com.tangem.feature.swap.domain.api.SwapRepository
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal class SwapDataModule {

    @Provides
    @Singleton
    internal fun provideSwapRepository(
        paraswapApi: com.tangem.datasource.api.swap.ParaswapApi,
        kyberSwapApi: com.tangem.datasource.api.swap.KyberSwapApi,
        acrossBridgeApi: AcrossBridgeApi,
        thorchainApi: ThorchainApi,
        odosApi: com.tangem.datasource.api.swap.OdosApi,
        coWSwapApi: com.tangem.datasource.api.swap.CoWSwapApi,
        veloraApi: com.tangem.datasource.api.swap.VeloraApi,
        liFiApi: com.tangem.datasource.api.swap.LiFiApi,
        providerRegistry: com.tangem.feature.swap.providers.DexProviderRegistry,
        coroutineDispatcher: CoroutineDispatcherProvider,
    ): SwapRepository {
        return RaksaSwapRepository(
            paraswapApi = paraswapApi,
            kyberSwapApi = kyberSwapApi,
            acrossBridgeApi = acrossBridgeApi,
            thorchainApi = thorchainApi,
            odosApi = odosApi,
            coWSwapApi = coWSwapApi,
            veloraApi = veloraApi,
            liFiApi = liFiApi,
            providerRegistry = providerRegistry,
            coroutineDispatcher = coroutineDispatcher,
        )
    }

    @Provides
    @Singleton
    fun provideSwapTransactionRepository(
        appPreferencesStore: AppPreferencesStore,
        responseCryptoCurrenciesFactory: ResponseCryptoCurrenciesFactory,
        networkFactory: NetworkFactory,
        singleAccountListSupplier: SingleAccountListSupplier,
        dispatcherProvider: CoroutineDispatcherProvider,
    ): SwapTransactionRepository {
        return DefaultSwapTransactionRepository(
            appPreferencesStore = appPreferencesStore,
            responseCryptoCurrenciesFactory = responseCryptoCurrenciesFactory,
            networkFactory = networkFactory,
            singleAccountListSupplier = singleAccountListSupplier,
            dispatchers = dispatcherProvider,
        )
    }

    @Provides
    @Singleton
    internal fun provideErrorsConverter(@NetworkMoshi moshi: Moshi): ErrorsDataConverter {
        val jsonAdapter = moshi.adapter(ExpressErrorResponse::class.java)
        return ErrorsDataConverter(jsonAdapter)
    }

    @Provides
    @Singleton
    internal fun provideSwapFeedbackRepository(
        api: SurveySparrowApi,
        environmentConfig: EnvironmentConfig,
    ): SwapFeedbackRepository {
        val rating = environmentConfig.surveySparrowSwapRating
            ?: return NoOpSwapFeedbackRepository()
        return DefaultSwapFeedbackRepository(
            api = api,
            surveyId = rating.surveyId,
            ratingQuestionId = rating.ratingQuestionId,
            feedbackQuestionId = rating.feedbackQuestionId,
        )
    }
}