package com.tangem.feature.swap.model

import com.tangem.domain.swap.models.StablecoinDetector
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

        // FROM is not stablecoin -> best stablecoin from any chain
        // Prefer same-chain, fallback to any chain
        val sameChain = availableTokens.filter {
            StablecoinDetector.isStablecoin(it.currency)
            && it.currency.network.rawId.lowercase() == fromToken.currency.network.rawId.lowercase()
            && it.isAvailableForSwap
        }
        val allChains = availableTokens.filter {
            StablecoinDetector.isStablecoin(it.currency) && it.isAvailableForSwap
        }
        return sameChain.maxByOrNull { it.status.value.fiatAmount.orZero() }
            ?: allChains.maxByOrNull { it.status.value.fiatAmount.orZero() }
    }
}
