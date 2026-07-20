package com.tangem.feature.swap.di

import com.tangem.feature.swap.providers.DexProvider
import com.tangem.feature.swap.providers.ParaswapDexProvider
import com.tangem.feature.swap.providers.KyberSwapDexProvider
import com.tangem.feature.swap.providers.OdosDexProvider
import com.tangem.feature.swap.providers.VeloraDexProvider
import com.tangem.feature.swap.providers.CoWSwapDexProvider
import com.tangem.feature.swap.providers.LiFiDexProvider
import com.tangem.datasource.api.swap.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object DexProviderModule {
    @Provides @IntoSet fun provideParaswap(api: ParaswapApi): DexProvider = ParaswapDexProvider(api)
    @Provides @IntoSet fun provideKyberSwap(api: KyberSwapApi): DexProvider = KyberSwapDexProvider(api)
    @Provides @IntoSet fun provideOdos(api: OdosApi): DexProvider = OdosDexProvider(api)
    @Provides @IntoSet fun provideVelora(api: VeloraApi): DexProvider = VeloraDexProvider(api)
    @Provides @IntoSet fun provideCoWSwap(api: CoWSwapApi): DexProvider = CoWSwapDexProvider(api)
    @Provides @IntoSet fun provideLiFi(api: LiFiApi): DexProvider = LiFiDexProvider(api)
}