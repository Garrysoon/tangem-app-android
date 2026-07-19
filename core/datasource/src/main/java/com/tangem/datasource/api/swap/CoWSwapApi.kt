package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/** CoW Swap API — gasless, intent-based. Free, no key. */
interface CoWSwapApi {

    @POST("{network}/api/v1/quote")
    suspend fun getQuote(
        @Path("network") network: String,
        @Body body: CoWQuoteRequest,
    ): CoWQuoteResponse
}

@JsonClass(generateAdapter = true)
data class CoWQuoteRequest(
    @Json(name = "sellToken") val sellToken: String,
    @Json(name = "buyToken") val buyToken: String,
    @Json(name = "sellAmountBeforeFee") val sellAmount: String,
    @Json(name = "kind") val kind: String = "sell",
    @Json(name = "from") val from: String? = null,
)

@JsonClass(generateAdapter = true)
data class CoWQuoteResponse(
    @Json(name = "quote") val quote: CoWQuote,
)

@JsonClass(generateAdapter = true)
data class CoWQuote(
    @Json(name = "sellToken") val sellToken: String,
    @Json(name = "buyToken") val buyToken: String,
    @Json(name = "sellAmount") val sellAmount: String,
    @Json(name = "buyAmount") val buyAmount: String,
    @Json(name = "feeAmount") val feeAmount: String?,
    @Json(name = "validTo") val validTo: Long?,
    @Json(name = "kind") val kind: String?,
    @Json(name = "partiallyFillable") val partiallyFillable: Boolean?,
)
