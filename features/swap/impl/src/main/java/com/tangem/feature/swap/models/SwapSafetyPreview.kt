package com.tangem.feature.swap.models

data class SwapSafetyPreview(
    val approveGasUsd: String = "0",
    val swapGasUsd: String = "0",
    val bridgeFeeUsd: String = "0",
    val totalFeeUsd: String = "0",
    val transactions: List<SwapTxPreview> = emptyList(),
    val estimatedFillTimeSec: Int = 0,
    val bridgeStatus: BridgeStatus = BridgeStatus.NOT_STARTED,
    val hasEnoughForGas: Boolean = true,
    val hasEnoughForSwap: Boolean = true,
    val gasReserveWarning: String? = null,
    val slippagePercent: Float = 1.0f,
)

data class SwapTxPreview(
    val step: Int,
    val title: String,
    val description: String,
    val fromAmount: String,
    val toAmount: String,
    val contractAddress: String?,
    val contractName: String,
    val estimatedGasUsd: String,
)

enum class BridgeStatus { NOT_STARTED, DEPOSITED, RELAYING, FILLED, FAILED, TIMEOUT }
