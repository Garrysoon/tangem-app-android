package com.tangem.feature.swap.data

import java.math.BigDecimal
import java.math.BigInteger

data class GasEstimate(
    val gasLimit: BigInteger,
    val gasPriceGwei: BigDecimal,
    val gasCostNative: BigDecimal,
    val gasCostUsd: BigDecimal,
    val label: String,
)

object GasEstimator {

    private val GAS_LIMITS = mapOf(
        "approve" to BigInteger.valueOf(46_000),
        "swap_paraswap" to BigInteger.valueOf(150_000),
        "swap_kyber" to BigInteger.valueOf(150_000),
        "bridge_across" to BigInteger.valueOf(200_000),
        "thorchain_swap" to BigInteger.valueOf(200_000),
    )

    private val GAS_PRICES_GWEI = mapOf(
        "ethereum" to BigDecimal("0.5"),
        "arbitrum" to BigDecimal("0.001"),
        "polygon" to BigDecimal("30"),
        "optimism" to BigDecimal("0.001"),
        "base" to BigDecimal("0.001"),
        "bsc" to BigDecimal("3"),
    )

    private val NATIVE_PRICE_USD = mapOf(
        "ethereum" to BigDecimal("2500"),
        "arbitrum" to BigDecimal("2500"),
        "polygon" to BigDecimal("0.5"),
        "optimism" to BigDecimal("2500"),
        "base" to BigDecimal("2500"),
        "bsc" to BigDecimal("600"),
        "bitcoin" to BigDecimal("100000"),
        "litecoin" to BigDecimal("100"),
        "tron" to BigDecimal("0.25"),
        "avalanche" to BigDecimal("35"),
    )

    private val NATIVE_DECIMALS = mapOf(
        "bitcoin" to 8,
        "litecoin" to 8,
        "tron" to 6,
    )

    // Fixed fee estimates for non-EVM chains (USD)
    private val FIXED_FEES_USD = mapOf(
        "bitcoin" to BigDecimal("2"),
        "litecoin" to BigDecimal("0.05"),
        "tron" to BigDecimal("0.10"),
    )

    fun estimate(txType: String, chain: String): GasEstimate {
        val normalizedChain = chain.lowercase().replace("-one", "").replace("-pos", "")
        val gasLimit = GAS_LIMITS[txType] ?: BigInteger.valueOf(100_000)

        // Non-EVM chains: use fixed fee estimate
        val fixedFee = FIXED_FEES_USD[normalizedChain]
        if (fixedFee != null) {
            return GasEstimate(
                gasLimit = BigInteger.ZERO,
                gasPriceGwei = BigDecimal.ZERO,
                gasCostNative = BigDecimal.ZERO,
                gasCostUsd = fixedFee,
                label = "$txType on $normalizedChain: ~$${fixedFee.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}",
            )
        }

        val gasPriceGwei = GAS_PRICES_GWEI[normalizedChain] ?: BigDecimal("10")
        val nativePriceUsd = NATIVE_PRICE_USD[normalizedChain] ?: BigDecimal("2500")
        val nativeDecimals = NATIVE_DECIMALS[normalizedChain] ?: 18

        val gasCostWei = gasLimit.toBigDecimal().multiply(gasPriceGwei).multiply(BigDecimal("1000000000"))
        val gasCostNative = gasCostWei.movePointLeft(nativeDecimals)
        val gasCostUsd = gasCostNative.multiply(nativePriceUsd)

        return GasEstimate(
            gasLimit = gasLimit,
            gasPriceGwei = gasPriceGwei,
            gasCostNative = gasCostNative,
            gasCostUsd = gasCostUsd,
            label = "$txType on $normalizedChain: ~$${gasCostUsd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}",
        )
    }

    fun totalUsd(gasEstimates: List<GasEstimate>): BigDecimal {
        return gasEstimates.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.gasCostUsd) }
    }

    fun formatUsd(amount: BigDecimal): String {
        val formatted = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        return "~\$$formatted"
    }
}
