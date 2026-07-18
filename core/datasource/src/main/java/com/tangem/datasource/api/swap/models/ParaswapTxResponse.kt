package com.tangem.datasource.api.swap.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ParaswapTxResponse(
    @Json(name = "to") val to: String,
    @Json(name = "data") val data: String,
    @Json(name = "value") val value: String?,
    @Json(name = "gasPrice") val gasPrice: String?,
    @Json(name = "gas") val gas: String?,
    @Json(name = "error") val error: String?,
)
