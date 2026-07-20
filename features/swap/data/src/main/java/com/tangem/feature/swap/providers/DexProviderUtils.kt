package com.tangem.feature.swap.providers

import java.math.BigDecimal

/** Sentinel address for native tokens in EVM chains */
internal val NATIVE_ADDR = "0xEeeeeEeeeEeEeeEeEeEeeEEEeEeeeeEeeeeEEeE"
internal val ZERO_ADDR = "0x0000000000000000000000000000000000000000"
internal val WETH_ADDR = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"

internal val CHAIN_IDS = mapOf("ethereum" to 1, "arbitrum" to 42161, "optimism" to 10, "base" to 8453, "polygon" to 137, "bsc" to 56, "avalanche" to 43114)

/** LI.FI uses its own chain IDs for non-EVM chains */
internal val LIFI_CHAIN_IDS = mapOf(
    "bitcoin" to "20000000000001",
    "tron" to "728126428",
    "solana" to "1151111081099710",
)

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
 * Convert token address for Paraswap/Velora (require 'ETH' symbol for native).
 */
internal fun forParaswap(addr: String?): String {
    if (isNativeToken(addr)) return "ETH"
    return addr ?: ""
}

internal fun safeTokenAddr(addr: String?, isUtxo: Boolean = false): String {
    if (addr.isNullOrBlank() || addr in listOf("0", "0x", "0x0")) return if (isUtxo) ZERO_ADDR else NATIVE_ADDR
    return addr
}

/**
 * Convert token address for LI.FI API.
 * - EVM native: 0x000...000 (NOT the 0xEeee... sentinel)
 * - BTC native: string "bitcoin"
 * - TRON native: string "trx"
 */
internal fun forLifiToken(addr: String?, chain: String): String {
    if (isNativeToken(addr)) {
        val normalized = normalizeChain(chain)
        return when {
            "bitcoin" in normalized -> "bitcoin"
            "tron" in normalized -> "trx"
            else -> ZERO_ADDR  // EVM native = 0x000...000 for LI.FI
        }
    }
    return addr ?: ""
}

internal fun rawToAmount(raw: String, decimals: Int): BigDecimal = (raw.toBigDecimalOrNull() ?: BigDecimal.ZERO).movePointLeft(decimals)
