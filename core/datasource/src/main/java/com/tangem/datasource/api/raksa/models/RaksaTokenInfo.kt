package com.tangem.datasource.api.raksa.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RaksaTokenInfo(
    @Json(name = "address") val address: String,
    @Json(name = "decimals") val decimals: Int,
)
