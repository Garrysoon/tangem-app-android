package com.tangem.feature.swap.providers

import arrow.core.Either
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.QuoteModel
import com.tangem.feature.swap.domain.models.domain.SwapDataModel

/**
 * Universal interface for DEX aggregator providers.
 * Each implementation encapsulates API calls, response parsing, and tx building
 * for a single DEX aggregator.
 *
 * Adding a new provider = new class + one line in DexProviderModule.
 */
interface DexProvider {

    /** Unique provider identifier (e.g. "paraswap", "lifi") */
    val providerId: String

    /** Display name (e.g. "Paraswap", "LI.FI") */
    val name: String

    /**
     * Check if this provider supports the given token pair and chain combination.
     * Used by the routing layer to determine which providers to query.
     */
    fun isSupported(fromNetwork: String, toNetwork: String): Boolean

    /**
     * Get a quote for the given token pair.
     * Returns the estimated output amount and optional allowance contract.
     */
    suspend fun getQuote(
        userWallet: UserWallet,
        fromContractAddress: String,
        fromNetwork: String,
        toContractAddress: String,
        toNetwork: String,
        fromAmount: String,
        fromDecimals: Int,
        toDecimals: Int,
        fromAddress: String?,
    ): Either<ExpressDataError, QuoteModel>

    /**
     * Build a swap/bridge transaction for execution.
     * Returns the transaction data (to, data, value, gas, allowance contract).
     */
    suspend fun buildTx(
        userWallet: UserWallet,
        fromContractAddress: String,
        fromNetwork: String,
        toContractAddress: String,
        toNetwork: String,
        fromAmount: String,
        fromDecimals: Int,
        toDecimals: Int,
        fromAddress: String,
        toAddress: String,
    ): Either<ExpressDataError, SwapDataModel>
}