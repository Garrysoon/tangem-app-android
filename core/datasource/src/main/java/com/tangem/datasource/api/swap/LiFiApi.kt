package com.tangem.datasource.api.swap

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * LI.FI meta-aggregator API.
 * Base URL: https://li.quest/v1
 * Free tier: 200 requests per 2 hours (no API key needed).
 * No KYC required.
 *
 * Covers: same-chain DEX swaps + cross-chain bridge+swap routing.
 * Aggregates: 1inch, 0x, Paraswap, KyberSwap, Odos, Stargate, Across, Hop, cBridge, and many more.
 */
interface LiFiApi {

    /**
     * Get a quote for a token transfer (same-chain or cross-chain).
     *
     * @param fromChain chain ID or chain key (e.g. "1" for Ethereum, "137" for Polygon)
     * @param toChain destination chain ID or key
     * @param fromToken source token address (or symbol like "USDC" or "0xeee...eee" for native)
     * @param toToken destination token address (or symbol)
     * @param fromAmount amount in smallest unit (e.g. 1000000 for 1 USDC with 6 decimals)
     * @param fromAddress sender wallet address
     * @param slippage max slippage as decimal (0.005 = 0.5%)
     * @param order FASTEST or CHEAPEST
     */
    @GET("v1/quote")
    suspend fun getQuote(
        @Query("fromChain") fromChain: String,
        @Query("toChain") toChain: String,
        @Query("fromToken") fromToken: String,
        @Query("toToken") toToken: String,
        @Query("fromAmount") fromAmount: String,
        @Query("fromAddress") fromAddress: String? = null,
        @Query("slippage") slippage: Float = 0.005f,
        @Query("order") order: String = "CHEAPEST",
    ): LiFiQuoteResponse
}

data class LiFiQuoteResponse(
    val id: String? = null,
    val type: String? = null, // "swap", "cross", "lifi"
    val tool: String? = null, // e.g. "1inch", "paraswap", "across"
    val action: LiFiAction? = null,
    val estimate: LiFiEstimate? = null,
    val transactionRequest: LiFiTransactionRequest? = null,
    val includedSteps: List<LiFiIncludedStep>? = null,
)

data class LiFiAction(
    val fromChainId: Long? = null,
    val toChainId: Long? = null,
    val fromToken: LiFiToken? = null,
    val toToken: LiFiToken? = null,
    val fromAmount: String? = null,
    val fromAddress: String? = null,
    val toAddress: String? = null,
    val slippage: Double? = null,
)

data class LiFiEstimate(
    val fromAmount: String? = null,
    val fromAmountUSD: String? = null,
    val toAmount: String? = null,
    val toAmountMin: String? = null,
    val toAmountUSD: String? = null,
    val approvalAddress: String? = null,
    val feeCosts: List<LiFiFeeCost>? = null,
    val gasCosts: List<LiFiGasCost>? = null,
    val executionDuration: Double? = null, // seconds
    val tool: String? = null,
)

data class LiFiToken(
    val address: String? = null,
    val decimals: Int? = null,
    val symbol: String? = null,
    val chainId: Long? = null,
    val name: String? = null,
    val coinKey: String? = null,
    val priceUSD: String? = null,
    val logoURI: String? = null,
)

data class LiFiTransactionRequest(
    val from: String? = null,
    val to: String? = null,
    val chainId: Long? = null,
    val data: String? = null, // hex calldata
    val value: String? = null, // hex value for native token
    val gasPrice: String? = null,
    val gasLimit: String? = null,
)

data class LiFiFeeCost(
    val name: String? = null,
    val description: String? = null,
    val percentage: String? = null,
    val token: LiFiToken? = null,
    val amount: String? = null,
    val amountUSD: String? = null,
    val included: Boolean? = null,
)

data class LiFiGasCost(
    val type: String? = null, // "SEND", "APPROVE", "SUM"
    val price: String? = null,
    val estimate: String? = null,
    val limit: String? = null,
    val amount: String? = null,
    val amountUSD: String? = null,
    val token: LiFiToken? = null,
)

data class LiFiIncludedStep(
    val id: String? = null,
    val type: String? = null, // "swap", "cross"
    val tool: String? = null,
    val toolDetails: LiFiToolDetails? = null,
    val action: LiFiAction? = null,
    val estimate: LiFiEstimate? = null,
)

data class LiFiToolDetails(
    val key: String? = null,
    val name: String? = null,
    val logoURI: String? = null,
)