package com.tangem.datasource.api.swap.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class KyberBuildResponse(
    @Json(name = "code") val code: Int,
    @Json(name = "message") val message: String?,
    @Json(name = "data") val data: KyberBuildData?,
)

@JsonClass(generateAdapter = true)
data class KyberBuildData(
    @Json(name = "data") val data: String,
    @Json(name = "routerAddress") val routerAddress: String,
    @Json(name = "gas") val gas: String?,
    @Json(name = "gasPrice") val gasPrice: String?,
)
