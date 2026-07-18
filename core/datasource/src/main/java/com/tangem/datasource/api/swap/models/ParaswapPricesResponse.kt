package com.tangem.datasource.api.swap.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ParaswapPricesResponse(
    @Json(name = "priceRoute") val priceRoute: ParaswapPriceRoute?,
    @Json(name = "error") val error: String?,
)

@JsonClass(generateAdapter = true)
data class ParaswapPriceRoute(
    @Json(name = "destAmount") val destAmount: String,
    @Json(name = "srcAmount") val srcAmount: String,
    @Json(name = "bestRoute") val bestRoute: Any?,
    @Json(name = "gasCost") val gasCost: String?,
    @Json(name = "tokenTransferProxy") val tokenTransferProxy: String?,
)
