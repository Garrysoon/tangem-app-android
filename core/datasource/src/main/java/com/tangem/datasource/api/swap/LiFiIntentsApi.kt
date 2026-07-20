package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * LI.FI Intents API for TRON and cross-chain swaps.
 * Base URL: https://order.li.fi
 * Uses CAIP-2 chains + native addresses (base58 for TRON).
 *
 * @see <a href="https://docs.li.fi/lifi-intents/intents-api/api-overview">API Overview</a>
 * @see <a href="https://docs.li.fi/lifi-intents/knowledge-database/tron-deltas">Tron vs EVM</a>
 */
interface LiFiIntentsApi {

    @POST("api/v1/integrator/quote/request")
    suspend fun requestQuote(
        @Body request: LiFiIntentsQuoteRequest,
    ): LiFiIntentsQuoteResponse
}

/** Request body for V1 integrator quote endpoint */
@JsonClass(generateAdapter = true)
data class LiFiIntentsQuoteRequest(
    @Json(name = "user") val user: LiFiIntentsUser,
    @Json(name = "intent") val intent: LiFiIntentsIntent,
    @Json(name = "supportedTypes") val supportedTypes: List<String> = listOf("oif-user-open-v0"),
)

@JsonClass(generateAdapter = true)
data class LiFiIntentsUser(
    @Json(name = "chain") val chain: String,
    @Json(name = "address") val address: String,
)

@JsonClass(generateAdapter = true)
data class LiFiIntentsIntent(
    @Json(name = "intentType") val intentType: String = "oif-swap",
    @Json(name = "inputs") val inputs: List<LiFiIntentsAsset>,
    @Json(name = "outputs") val outputs: List<LiFiIntentsAsset>,
    @Json(name = "swapType") val swapType: String = "exact-input",
)

@JsonClass(generateAdapter = true)
data class LiFiIntentsAsset(
    @Json(name = "chain") val chain: String,
    @Json(name = "user") val user: String? = null,
    @Json(name = "receiver") val receiver: String? = null,
    @Json(name = "asset") val asset: String,
    @Json(name = "amount") val amount: String?,
)

/** Response from V1 integrator quote endpoint */
@JsonClass(generateAdapter = true)
data class LiFiIntentsQuoteResponse(
    @Json(name = "quotes") val quotes: List<LiFiIntentsQuote>?,
    @Json(name = "error") val error: String? = null,
)

/**
 * Quote object matching the V1 integrator response format.
 * @see <a href="https://docs.li.fi/lifi-intents/knowledge-database/tron-deltas">Tron Deltas - Response format</a>
 */
@JsonClass(generateAdapter = true)
data class LiFiIntentsQuote(
    @Json(name = "quoteId") val quoteId: String? = null,
    @Json(name = "preview") val preview: LiFiIntentsPreview? = null,
    @Json(name = "source") val source: LiFiIntentsQuoteSource? = null,
    @Json(name = "validUntil") val validUntil: Long? = null,
    @Json(name = "partialFill") val partialFill: Boolean? = null,
    @Json(name = "failureHandling") val failureHandling: String? = null,
)

@JsonClass(generateAdapter = true)
data class LiFiIntentsPreview(
    @Json(name = "inputs") val inputs: List<LiFiIntentsAsset>? = null,
    @Json(name = "outputs") val outputs: List<LiFiIntentsAsset>? = null,
)

@JsonClass(generateAdapter = true)
data class LiFiIntentsQuoteSource(
    @Json(name = "solver") val solver: String? = null,
)
