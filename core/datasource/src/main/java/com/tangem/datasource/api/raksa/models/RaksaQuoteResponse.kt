package com.tangem.datasource.api.raksa.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RaksaQuoteResponse(
    @Json(name = "chain") val chain: String,
    @Json(name = "sell") val sell: RaksaTokenLeg,
    @Json(name = "buy") val buy: RaksaTokenLeg,
    @Json(name = "quotes") val quotes: List<RaksaQuote>,
    @Json(name = "errors") val errors: List<RaksaQuoteError>,
    @Json(name = "elapsed_ms") val elapsedMs: Int,
    @Json(name = "best_source") val bestSource: String?,
)

@JsonClass(generateAdapter = true)
data class RaksaTokenLeg(
    @Json(name = "symbol") val symbol: String,
    @Json(name = "address") val address: String,
    @Json(name = "decimals") val decimals: Int,
    @Json(name = "amount") val amount: String? = null,
    @Json(name = "amount_raw") val amountRaw: String? = null,
)

@JsonClass(generateAdapter = true)
data class RaksaQuote(
    @Json(name = "source") val source: String,
    @Json(name = "amount_out") val amountOut: String,
    @Json(name = "amount_out_raw") val amountOutRaw: String,
    @Json(name = "gas") val gas: Int? = null,
    @Json(name = "gas_price_gwei") val gasPriceGwei: Double? = null,
    @Json(name = "price_impact_pct") val priceImpactPct: Double? = null,
    @Json(name = "route_summary") val routeSummary: String? = null,
    @Json(name = "winner") val winner: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class RaksaQuoteError(
    @Json(name = "source") val source: String,
    @Json(name = "error") val error: String,
    @Json(name = "status") val status: Int? = null,
)
