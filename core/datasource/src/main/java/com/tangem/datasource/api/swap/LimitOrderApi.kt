package com.tangem.datasource.api.swap

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

interface LimitOrderApi {
    @POST("/api/orders/create")
    suspend fun createOrder(@Body body: LimitOrderRequest): LimitOrderResponse

    @POST("/api/orders/cancel")
    suspend fun cancelOrder(@Body body: CancelOrderRequest): CancelOrderResponse
}

@JsonClass(generateAdapter = true)
data class LimitOrderRequest(
    @Json(name = "chain") val chain: String,
    @Json(name = "protocol") val protocol: String,
    @Json(name = "maker_token") val makerToken: String,
    @Json(name = "taker_token") val takerToken: String,
    @Json(name = "maker_amount") val makerAmount: Long,
    @Json(name = "target_price") val targetPrice: Double,
    @Json(name = "expiry_hours") val expiryHours: Int = 24,
    @Json(name = "private_key") val privateKey: String? = null,
)

@JsonClass(generateAdapter = true)
data class LimitOrderResponse(
    @Json(name = "protocol") val protocol: String,
    @Json(name = "chain") val chain: String,
    @Json(name = "chain_id") val chainId: Int,
    @Json(name = "order") val order: Map<String, Any>,
    @Json(name = "signature") val signature: String,
    @Json(name = "maker") val maker: String,
    @Json(name = "maker_amount") val makerAmount: Long,
    @Json(name = "taker_amount") val takerAmount: Long,
    @Json(name = "target_price") val targetPrice: Double,
    @Json(name = "expiry") val expiry: Long,
)

@JsonClass(generateAdapter = true)
data class CancelOrderRequest(
    @Json(name = "chain") val chain: String,
    @Json(name = "order") val order: Map<String, Any>,
    @Json(name = "signature") val signature: String,
    @Json(name = "protocol") val protocol: String,
)

@JsonClass(generateAdapter = true)
data class CancelOrderResponse(
    @Json(name = "protocol") val protocol: String,
    @Json(name = "chain") val chain: String,
    @Json(name = "action") val action: String,
    @Json(name = "to") val to: String,
    @Json(name = "data") val data: String,
    @Json(name = "gas_estimate") val gasEstimate: Int?,
)
