package com.tangem.feature.swap.providers

import java.math.BigDecimal

/** Sentinel address for native tokens in EVM chains */
internal val NATIVE_ADDR = "0xEeeeeEeeeEeEeeEeEeEeeEEEeEeeeeEeeeeEEeE"
internal val ZERO_ADDR = "0x0000000000000000000000000000000000000000"
internal val WETH_ADDR = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"

internal val CHAIN_IDS = mapOf("ethereum" to 1, "arbitrum" to 42161, "optimism" to 10, "base" to 8453, "polygon" to 137, "bsc" to 56, "avalanche" to 43114)

internal fun normalizeChain(chain: String): String = chain.lowercase().replace("-one", "").replace("-pos", "")
internal fun chainToId(chain: String): Int = CHAIN_IDS[normalizeChain(chain)] ?: 1
internal fun isUtxoChain(chain: String): Boolean = normalizeChain(chain).let { "bitcoin" in it || "litecoin" in it }

/** Detect if an address represents a native token */
internal fun isNativeToken(addr: String?): Boolean {
    if (addr.isNullOrBlank()) return true
    val lower = addr.lowercase()
    return lower == NATIVE_ADDR.lowercase() || lower == ZERO_ADDR.lowercase()
        || lower == "0" || lower == "0x" || lower == "0x0"
}

/**
 * Convert token address for specific provider APIs.
 * Paraswap/Velora require 'ETH' symbol for native tokens (not hex address).
 * Other APIs (Odos, CoW, LI.FI) accept hex sentinel.
 */
internal fun forParaswap(addr: String?): String {
    if (isNativeToken(addr)) return "ETH"
    return addr ?: ""
}

internal fun safeTokenAddr(addr: String?, isUtxo: Boolean = false): String {
    if (addr.isNullOrBlank() || addr in listOf("0", "0x", "0x0")) return if (isUtxo) ZERO_ADDR else NATIVE_ADDR
    return addr
}

internal fun rawToAmount(raw: String, decimals: Int): BigDecimal = (raw.toBigDecimalOrNull() ?: BigDecimal.ZERO).movePointLeft(decimals)
