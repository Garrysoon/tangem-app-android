package com.tangem.feature.swap.model

import com.tangem.domain.swap.models.StablecoinDetector
import com.tangem.feature.swap.model.StablecoinRateCache
import com.tangem.domain.swap.models.SwapCurrencyStatus
import com.tangem.utils.extensions.orZero

object TokenPairSuggester {

    fun suggest(
        fromToken: SwapCurrencyStatus,
        availableTokens: List<SwapCurrencyStatus>,
        alreadySelectedTo: SwapCurrencyStatus?,
    ): SwapCurrencyStatus? {
        if (alreadySelectedTo != null) return null

        // FROM is stablecoin -> suggest BTC with highest fiat balance
        if (StablecoinDetector.isStablecoin(fromToken.currency)) {
            return availableTokens
                .filter { StablecoinDetector.isBtc(it.currency) && it.isAvailableForSwap }
                .maxByOrNull { it.status.value.fiatAmount.orZero() }
        }

        // FROM is not stablecoin -> best stablecoin by cached exchange rate
        // Prefer same-chain, fallback to any chain
        val sameChain = availableTokens.filter {
            StablecoinDetector.isStablecoin(it.currency)
            && it.currency.network.rawId.lowercase() == fromToken.currency.network.rawId.lowercase()
            && it.isAvailableForSwap
        }
        val fromId = fromToken.currency.id.value
        val allStablecoins = availableTokens.filter {
            StablecoinDetector.isStablecoin(it.currency) && it.isAvailableForSwap
        }
        return allStablecoins.maxByOrNull { stablecoin: SwapCurrencyStatus ->
            val cachedRate = StablecoinRateCache.getRate(fromId, stablecoin.currency.id.value)
            cachedRate ?: stablecoin.status.value.fiatAmount.orZero()
        }
    }
}
