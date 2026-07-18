package com.tangem.datasource.api.raksa

import com.tangem.datasource.api.raksa.models.RaksaQuoteResponse
import com.tangem.datasource.api.raksa.models.RaksaSwapResponse
import com.tangem.datasource.api.raksa.models.RaksaTokenInfo
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RaksaApi {

    @GET("/api/chains")
    suspend fun getChains(): Map<String, Int>

    @GET("/api/tokens/{chain}")
    suspend fun getTokens(@Path("chain") chain: String): Map<String, RaksaTokenInfo>

    @GET("/api/quote")
    suspend fun getQuote(
        @Query("chain") chain: String,
        @Query("sell") sell: String,
        @Query("buy") buy: String,
        @Query("amount") amount: String,
        @Query("slippage_pct") slippage: Float = 1f,
    ): RaksaQuoteResponse

    @GET("/api/swap")
    suspend fun buildSwapTx(
        @Query("source") source: String,
        @Query("chain") chain: String,
        @Query("sell") sell: String,
        @Query("buy") buy: String,
        @Query("amount") amount: String,
        @Query("sender") sender: String,
        @Query("recipient") recipient: String? = null,
        @Query("slippage_pct") slippage: Float = 1f,
    ): RaksaSwapResponse
}
