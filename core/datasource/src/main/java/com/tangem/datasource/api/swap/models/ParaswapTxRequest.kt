package com.tangem.datasource.api.swap.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ParaswapTxRequest(
    @Json(name = "srcToken") val srcToken: String,
    @Json(name = "destToken") val destToken: String,
    @Json(name = "srcAmount") val srcAmount: String,
    @Json(name = "destAmount") val destAmount: String,
    @Json(name = "priceRoute") val priceRoute: ParaswapPriceRoute,
    @Json(name = "userAddress") val userAddress: String,
    @Json(name = "receiver") val receiver: String,
    @Json(name = "srcDecimals") val srcDecimals: Int,
    @Json(name = "destDecimals") val destDecimals: Int,
)
