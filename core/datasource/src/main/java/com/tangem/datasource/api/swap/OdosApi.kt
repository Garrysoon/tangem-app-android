package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Odos DEX Aggregator API — free, no API key required.
 * Base URL: https://api.odos.xyz
 * Docs: https://docs.odyssai.exchange/build/api-docs
 */
interface OdosApi {

    /** Get optimal swap route. Quote expires in 60 seconds. */
    @POST("sor/quote/v2")
    suspend fun getQuote(@Body body: OdosQuoteRequest): OdosQuoteResponse

    /** Assemble executable transaction from quote pathId. Must be called within 60 seconds. */
    @POST("sor/assemble")
    suspend fun assembleTransaction(@Body body: OdosAssembleRequest): OdosAssembleResponse

    /** Get supported chains */
    @GET("info/chains")
    suspend fun getChains(): List<Int>

    /** Get token list for a chain */
    @GET("info/tokens/{chainId}")
    suspend fun getTokens(@Path("chainId") chainId: Int): Map<String, Any>
}

@JsonClass(generateAdapter = true)
data class OdosQuoteRequest(
    @Json(name = "chainId") val chainId: Int,
    @Json(name = "inputTokens") val inputTokens: List<OdosTokenAmount>,
    @Json(name = "outputTokens") val outputTokens: List<OdosTokenProportion>,
    @Json(name = "slippageLimitPercent") val slippageLimitPercent: Float = 0.5f,
    @Json(name = "userAddr") val userAddr: String? = null,
    @Json(name = "compact") val compact: Boolean = true,
    @Json(name = "disableRFQs") val disableRFQs: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class OdosTokenAmount(
    @Json(name = "tokenAddress") val tokenAddress: String,
    @Json(name = "amount") val amount: String,
)

@JsonClass(generateAdapter = true)
data class OdosTokenProportion(
    @Json(name = "tokenAddress") val tokenAddress: String,
    @Json(name = "proportion") val proportion: Float = 1f,
)

@JsonClass(generateAdapter = true)
data class OdosQuoteResponse(
    @Json(name = "pathId") val pathId: String,
    @Json(name = "outTokens") val outTokens: List<String>?,
    @Json(name = "outAmounts") val outAmounts: List<String>?,
    @Json(name = "gasEstimate") val gasEstimate: Long?,
    @Json(name = "gasEstimateValue") val gasEstimateValue: Double?,
    @Json(name = "netOutValue") val netOutValue: Double?,
    @Json(name = "priceImpact") val priceImpact: Double?,
    @Json(name = "percentDiff") val percentDiff: Double?,
)

@JsonClass(generateAdapter = true)
data class OdosAssembleRequest(
    @Json(name = "userAddr") val userAddr: String,
    @Json(name = "pathId") val pathId: String,
    @Json(name = "simulate") val simulate: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class OdosAssembleResponse(
    @Json(name = "gasEstimate") val gasEstimate: Long?,
    @Json(name = "outAmounts") val outAmounts: List<String>?,
    @Json(name = "outTokens") val outTokens: List<String>?,
    @Json(name = "netOutValue") val netOutValue: Double?,
    @Json(name = "transaction") val transaction: OdosTransaction?,
)

@JsonClass(generateAdapter = true)
data class OdosTransaction(
    @Json(name = "to") val to: String,
    @Json(name = "data") val data: String,
    @Json(name = "value") val value: String,
    @Json(name = "gas") val gas: Long,
    @Json(name = "chainId") val chainId: Int,
)
