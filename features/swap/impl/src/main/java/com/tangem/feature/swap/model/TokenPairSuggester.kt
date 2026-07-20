package com.tangem.feature.swap.model

import com.tangem.domain.swap.models.StablecoinDetector
import com.tangem.domain.swap.models.SwapCurrencyStatus
import com.tangem.utils.extensions.orZero

object TokenPairSuggester {

    private val UTXO_VIRTUAL_DEST_CHAIN = "ethereum"

    fun suggest(
        fromToken: SwapCurrencyStatus,
        availableTokens: List<SwapCurrencyStatus>,
        alreadySelectedTo: SwapCurrencyStatus?,
    ): SwapCurrencyStatus? {
        if (alreadySelectedTo != null) return null

        val fromRawId = fromToken.currency.network.rawId.lowercase()
        val isUtxo = fromRawId.contains("bitcoin") || fromRawId.contains("litecoin")

        val targetChain = if (isUtxo) UTXO_VIRTUAL_DEST_CHAIN else fromRawId

        val candidates = availableTokens.filter {
            it.currency.network.rawId.lowercase().let { chain ->
                chain == targetChain || (isUtxo && chain.startsWith(targetChain))
            } && it.isAvailableForSwap
        }

        return if (StablecoinDetector.isStablecoin(fromToken.currency)) {
            candidates.filter { StablecoinDetector.isBtc(it.currency) }
                .maxByOrNull { it.status.value.fiatAmount.orZero() }
        } else {
            candidates.filter { StablecoinDetector.isStablecoin(it.currency) }
                .maxByOrNull { it.status.value.fiatAmount.orZero() }
        }
    }
}
