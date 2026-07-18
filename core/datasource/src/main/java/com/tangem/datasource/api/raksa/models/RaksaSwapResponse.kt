package com.tangem.datasource.api.raksa.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RaksaSwapResponse(
    @Json(name = "source") val source: String,
    @Json(name = "chain") val chain: String,
    @Json(name = "to") val to: String,
    @Json(name = "data") val data: String,
    @Json(name = "value") val value: String,
    @Json(name = "gas") val gas: Int? = null,
    @Json(name = "gas_price_gwei") val gasPriceGwei: Double? = null,
    @Json(name = "allowance_target") val allowanceTarget: String,
    @Json(name = "sell_token") val sellToken: String,
    @Json(name = "buy_token") val buyToken: String,
    @Json(name = "amount_in_raw") val amountInRaw: String,
    @Json(name = "amount_out_raw") val amountOutRaw: String,
    @Json(name = "min_amount_out_raw") val minAmountOutRaw: String,
)
