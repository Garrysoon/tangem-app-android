package com.tangem.feature.swap.providers

import java.math.BigDecimal

internal val NATIVE_ADDR = "0xEeeeeEeeeEeEeeEeEeEeeEEEeEeeeeEeeeeEEeE"
internal val CHAIN_IDS = mapOf("ethereum" to 1, "arbitrum" to 42161, "optimism" to 10, "base" to 8453, "polygon" to 137, "bsc" to 56, "avalanche" to 43114)
internal fun normalizeChain(chain: String): String = chain.lowercase().replace("-one", "").replace("-pos", "")
internal fun chainToId(chain: String): Int = CHAIN_IDS[normalizeChain(chain)] ?: 1
internal fun isUtxoChain(chain: String): Boolean = normalizeChain(chain).let { "bitcoin" in it || "litecoin" in it }
internal fun safeTokenAddr(addr: String?, isUtxo: Boolean = false): String {
    if (addr.isNullOrBlank() || addr in listOf("0", "0x")) return if (isUtxo) "0x0000000000000000000000000000000000000000" else NATIVE_ADDR
    return addr
}
internal fun rawToAmount(raw: String, decimals: Int): BigDecimal = (raw.toBigDecimalOrNull() ?: BigDecimal.ZERO).movePointLeft(decimals)