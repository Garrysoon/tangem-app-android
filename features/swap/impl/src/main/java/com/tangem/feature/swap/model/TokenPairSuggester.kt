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

        val sameChain = availableTokens.filter {
            it.currency.network.rawId == fromToken.currency.network.rawId &&
                it.isAvailableForSwap
        }

        return if (StablecoinDetector.isStablecoin(fromToken.currency)) {
            sameChain
                .filter { StablecoinDetector.isBtc(it.currency) }
                .maxByOrNull { it.status.value.fiatAmount.orZero() }
        } else {
            sameChain
                .filter { StablecoinDetector.isStablecoin(it.currency) }
                .maxByOrNull { it.status.value.fiatAmount.orZero() }
        }
    }
}
