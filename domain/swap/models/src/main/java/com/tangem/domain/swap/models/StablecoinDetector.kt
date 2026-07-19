package com.tangem.domain.swap.models

import com.tangem.domain.models.currency.CryptoCurrency

object StablecoinDetector {

    private val STABLECOIN_SYMBOLS: Map<String, Int> = listOf(
        "USDT", "USDC", "USDe", "DAI", "PYUSD",
    ).withIndex().associate { (rank, symbol) -> symbol.uppercase() to rank }

    private const val BTC_SYMBOL = "BTC"

    fun isStablecoin(currency: CryptoCurrency): Boolean {
        val normalized = currency.symbol.substringBefore('.').uppercase()
        return normalized in STABLECOIN_SYMBOLS
    }

    fun isBtc(currency: CryptoCurrency): Boolean {
        return currency.symbol.uppercase() == BTC_SYMBOL
    }

    fun stablecoinPriority(currency: CryptoCurrency): Int {
        val normalized = currency.symbol.substringBefore('.').uppercase()
        return STABLECOIN_SYMBOLS[normalized] ?: Int.MAX_VALUE
    }
}
