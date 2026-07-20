package com.tangem.datasource.api.swap

import com.tangem.datasource.api.swap.models.ParaswapPricesResponse
import com.tangem.datasource.api.swap.models.ParaswapTxRequest
import com.tangem.datasource.api.swap.models.ParaswapTxResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Velora DEX Aggregator API — identical to Paraswap.
 * Base URL: https://api.velora.xyz
 * Free, no API key required.
 */
interface VeloraApi {

    @GET("prices")
    suspend fun getPrices(
        @Query("srcToken") srcToken: String,
        @Query("destToken") destToken: String,
        @Query("amount") amount: String,
        @Query("srcDecimals") srcDecimals: Int,
        @Query("destDecimals") destDecimals: Int,
        @Query("side") side: String = "SELL",
        @Query("network") network: Int,
        @Query("version") version: String = "6.2",
    ): ParaswapPricesResponse

    @POST("transactions/{chainId}")
    suspend fun buildTransaction(
        @Path("chainId") chainId: Int,
        @Query("ignoreChecks") ignoreChecks: String = "true",
        @Body body: ParaswapTxRequest,
    ): ParaswapTxResponse
}
