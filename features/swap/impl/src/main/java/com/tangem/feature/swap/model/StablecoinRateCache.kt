package com.tangem.feature.swap.model

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches exchange rates for stablecoin pairs.
 * Rates are refreshed periodically (every 5 minutes).
 * Used by TokenPairSuggester to pick the best stablecoin by exchange rate.
 */
object StablecoinRateCache {

    private data class CachedRate(
        val rate: BigDecimal,
        val timestamp: Long = System.currentTimeMillis(),
    )

    // Key: "BTC_ID -> STABLECOIN_ID" e.g. "bitcoin -> USDT"
    private val rateCache = ConcurrentHashMap<String, CachedRate>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    fun cacheRate(fromId: String, toId: String, rate: BigDecimal) {
        rateCache["$fromId->$toId"] = CachedRate(rate)
    }

    fun getRate(fromId: String, toId: String): BigDecimal? {
        val cached = rateCache["$fromId->$toId"] ?: return null
        if (System.currentTimeMillis() - cached.timestamp > CACHE_TTL_MS) {
            rateCache.remove("$fromId->$toId")
            return null
        }
        return cached.rate
    }

    fun isStale(): Boolean {
        return rateCache.values.all { System.currentTimeMillis() - it.timestamp > CACHE_TTL_MS }
    }

    fun clear() {
        rateCache.clear()
    }
}
