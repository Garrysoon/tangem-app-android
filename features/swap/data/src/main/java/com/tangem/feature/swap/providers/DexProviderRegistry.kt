package com.tangem.feature.swap.providers

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DexProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards DexProvider>,
) {
    private val byId = providers.associateBy { it.providerId.lowercase() }
    fun getProvider(id: String): DexProvider? = byId[id.lowercase()]
    fun getSupportedProviders(fromNetwork: String, toNetwork: String): List<DexProvider> =
        byId.values.filter { it.isSupported(fromNetwork, toNetwork) }
    fun getAllProviders(): List<DexProvider> = byId.values.toList()
}