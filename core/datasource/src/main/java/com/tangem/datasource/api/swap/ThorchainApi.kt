package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/** THORChain bridge API. Free, no key. Supports BTC/LTC/AVAX/ETH. */
interface ThorchainApi {
    @GET("quote/swap")
    suspend fun getQuote(
        @Query("amount") amount: String,
        @Query("from_asset") fromAsset: String,
        @Query("to_asset") toAsset: String,
    ): ThorchainQuoteResponse
}

@JsonClass(generateAdapter = true)
data class ThorchainQuoteResponse(
    @com.squareup.moshi.Json(name = "expected_amount_out") val expectedAmountOut: String?,
    @com.squareup.moshi.Json(name = "fees") val fees: ThorchainFees?,
    @com.squareup.moshi.Json(name = "error") val error: String?,
)

@JsonClass(generateAdapter = true)
data class ThorchainFees(
    @com.squareup.moshi.Json(name = "total") val total: String?,
    @com.squareup.moshi.Json(name = "asset") val asset: String?,
)
