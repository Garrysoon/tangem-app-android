package com.tangem.feature.swap.data

import java.math.BigDecimal

/**
 * Validates that the user has sufficient balance for all swap transactions
 * before executing. Prevents failed transactions and wasted gas.
 */
data class BalanceValidation(
    val hasEnoughForSwap: Boolean = true,
    val hasEnoughForGas: Boolean = true,
    val swapAmount: String = "",
    val balance: String = "",
    val gasReserveWarning: String? = null,
    val approvalNeeded: Boolean = false,
) {
    val isAllValid: Boolean get() = hasEnoughForSwap && hasEnoughForGas
}

object BalanceValidator {

    // Minimum gas reserve per chain (in native token)
    private val GAS_RESERVES = mapOf(
        "ethereum" to BigDecimal("0.005"),  // ~$300 at $65k
        "arbitrum" to BigDecimal("0.001"),  // ~$0.5
        "polygon" to BigDecimal("0.01"),    // ~$0.01
        "optimism" to BigDecimal("0.001"),  // ~$0.5
        "base" to BigDecimal("0.001"),      // ~$0.5
        "bsc" to BigDecimal("0.001"),       // ~$0.6
    )

    // Estimated gas cost per transaction type per chain (in native token)
    private val GAS_ESTIMATES = mapOf(
        "ethereum" to mapOf(
            "approve" to BigDecimal("0.00008"),   // ~$5
            "swap" to BigDecimal("0.0002"),       // ~$13
            "bridge" to BigDecimal("0.00005"),    // ~$3
        ),
        "arbitrum" to mapOf(
            "approve" to BigDecimal("0.0000001"),
            "swap" to BigDecimal("0.0000005"),
            "bridge" to BigDecimal("0.0000003"),
        ),
    )

    fun validate(
        fromBalance: BigDecimal,
        fromChain: String,
        swapAmount: BigDecimal,
        isSwapAndBridge: Boolean = false,
        isApprovalNeeded: Boolean = false,
        ethPriceUsd: BigDecimal = BigDecimal("65000"),
    ): BalanceValidation {
        val gasReserve = GAS_RESERVES[fromChain.lowercase().replace("-one", "")] ?: BigDecimal("0.001")
        val gasEstimates = GAS_ESTIMATES[fromChain.lowercase().replace("-one", "")] ?: GAS_ESTIMATES["ethereum"]!!

        var totalGasNeeded = BigDecimal.ZERO
        if (isApprovalNeeded) totalGasNeeded += gasEstimates["approve"] ?: BigDecimal.ZERO
        totalGasNeeded += gasEstimates["swap"] ?: BigDecimal.ZERO
        if (isSwapAndBridge) totalGasNeeded += gasEstimates["bridge"] ?: BigDecimal.ZERO
        totalGasNeeded += gasReserve // safety margin

        val hasEnoughForGas = fromBalance >= totalGasNeeded
        val hasEnoughForSwap = fromBalance >= swapAmount + totalGasNeeded

        val gasReserveUsd = totalGasNeeded.multiply(ethPriceUsd)

        return BalanceValidation(
            hasEnoughForSwap = hasEnoughForSwap,
            hasEnoughForGas = hasEnoughForGas,
            swapAmount = swapAmount.toPlainString(),
            balance = fromBalance.toPlainString(),
            gasReserveWarning = if (!hasEnoughForGas) "Insufficient balance for swap + gas (~$${gasReserveUsd.setScale(0)} needed)" else null,
            approvalNeeded = isApprovalNeeded,
        )
    }
}
