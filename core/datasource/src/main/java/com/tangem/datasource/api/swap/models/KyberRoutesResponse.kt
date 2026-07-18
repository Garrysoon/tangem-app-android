package com.tangem.datasource.api.swap.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class KyberRoutesResponse(
    @Json(name = "code") val code: Int,
    @Json(name = "message") val message: String?,
    @Json(name = "data") val data: KyberRoutesData?,
)

@JsonClass(generateAdapter = true)
data class KyberRoutesData(
    @Json(name = "routeSummary") val routeSummary: KyberRouteSummary?,
)

@JsonClass(generateAdapter = true)
data class KyberRouteSummary(
    @Json(name = "amountIn") val amountIn: String,
    @Json(name = "amountOut") val amountOut: String,
    @Json(name = "tokenIn") val tokenIn: String,
    @Json(name = "tokenOut") val tokenOut: String,
    @Json(name = "gas") val gas: String?,
    @Json(name = "gasPrice") val gasPrice: String?,
)
