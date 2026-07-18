package com.tangem.datasource.api.swap.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class KyberBuildRequest(
    @Json(name = "routeSummary") val routeSummary: KyberRouteSummary,
    @Json(name = "sender") val sender: String,
    @Json(name = "recipient") val recipient: String,
    @Json(name = "slippageTolerance") val slippageTolerance: Int,
    @Json(name = "deadline") val deadline: Int = 0,
    @Json(name = "source") val source: String = "tangem",
)
