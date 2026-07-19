package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/** Across Protocol V4 bridge API. Free, no key. */
interface AcrossBridgeApi {
    @GET("suggested-fees")
    suspend fun getSuggestedFees(
        @Query("token") token: String,
        @Query("outputToken") outputToken: String?,
        @Query("originChainId") originChainId: Int,
        @Query("destinationChainId") destinationChainId: Int,
        @Query("amount") amount: String,
        @Query("depositor") depositor: String?,
        @Query("recipient") recipient: String?,
    ): AcrossFeesResponse
}

@JsonClass(generateAdapter = true)
data class AcrossFeesResponse(
    @com.squareup.moshi.Json(name = "relayFeeTotal") val relayFeeTotal: String?,
    @com.squareup.moshi.Json(name = "lpFeeTotal") val lpFeeTotal: String?,
    @com.squareup.moshi.Json(name = "estimatedFillTime") val estimatedFillTime: Int?,
    @com.squareup.moshi.Json(name = "isAmountTooLow") val isAmountTooLow: Boolean?,
    @com.squareup.moshi.Json(name = "isBridgePaused") val isBridgePaused: Boolean?,
    @com.squareup.moshi.Json(name = "outputAmount") val outputAmount: String?,
    @com.squareup.moshi.Json(name = "fillDeadline") val fillDeadline: String?,
    @com.squareup.moshi.Json(name = "limits") val limits: AcrossLimits?,
    @com.squareup.moshi.Json(name = "estimatedFillTimeSec") val estimatedFillTimeSec: Int?,
    @com.squareup.moshi.Json(name = "inputToken") val inputToken: AcrossTokenInfo?,
    @com.squareup.moshi.Json(name = "outputToken") val outputTokenInfo: AcrossTokenInfo?,
)

@JsonClass(generateAdapter = true)
data class AcrossLimits(
    @com.squareup.moshi.Json(name = "minDeposit") val minDeposit: String?,
    @com.squareup.moshi.Json(name = "maxDeposit") val maxDeposit: String?,
    @com.squareup.moshi.Json(name = "maxDepositInstant") val maxDepositInstant: String?,
    @com.squareup.moshi.Json(name = "recommendedDepositInstant") val recommendedDepositInstant: String?,
)

@JsonClass(generateAdapter = true)
data class AcrossTokenInfo(
    @com.squareup.moshi.Json(name = "address") val address: String?,
    @com.squareup.moshi.Json(name = "symbol") val symbol: String?,
    @com.squareup.moshi.Json(name = "decimals") val decimals: Int?,
    @com.squareup.moshi.Json(name = "chainId") val chainId: Int?,
)
