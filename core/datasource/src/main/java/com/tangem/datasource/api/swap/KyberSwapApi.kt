package com.tangem.datasource.api.swap

import com.tangem.datasource.api.swap.models.KyberBuildRequest
import com.tangem.datasource.api.swap.models.KyberBuildResponse
import com.tangem.datasource.api.swap.models.KyberRoutesResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * KyberSwap Aggregator API — free, no key required.
 * @see <a href="https://docs.kyberswap.com/">Docs</a>
 */
interface KyberSwapApi {

    @GET("{chain}/api/v1/routes")
    suspend fun getRoutes(
        @Path("chain") chain: String,
        @Query("tokenIn") tokenIn: String,
        @Query("tokenOut") tokenOut: String,
        @Query("amountIn") amountIn: String,
        @Query("gasInclude") gasInclude: String = "true",
    ): KyberRoutesResponse

    @POST("{chain}/api/v1/route/build")
    suspend fun buildRoute(
        @Path("chain") chain: String,
        @Body body: KyberBuildRequest,
    ): KyberBuildResponse
}
