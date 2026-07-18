package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/** Across Protocol V4 bridge API. Free, no key. */
interface AcrossBridgeApi {
    @GET("suggested-fees")
    suspend fun getSuggestedFees(
        @Query("inputToken") inputToken: String,
        @Query("outputToken") outputToken: String,
        @Query("originChainId") originChainId: Int,
        @Query("destinationChainId") destinationChainId: Int,
        @Query("amount") amount: String,
    ): AcrossFeesResponse
}

@JsonClass(generateAdapter = true)
data class AcrossFeesResponse(
    @com.squareup.moshi.Json(name = "relayFeeTotal") val relayFeeTotal: String?,
    @com.squareup.moshi.Json(name = "lpFeeTotal") val lpFeeTotal: String?,
    @com.squareup.moshi.Json(name = "estimatedFillTime") val estimatedFillTime: Int?,
    @com.squareup.moshi.Json(name = "isAmountTooLow") val isAmountTooLow: Boolean?,
)
