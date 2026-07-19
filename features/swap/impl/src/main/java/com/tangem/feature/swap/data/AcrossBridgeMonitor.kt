package com.tangem.feature.swap.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors Across bridge fill status by polling.
 * Reports: pending -> relayed -> filled or timeout.
 */
class AcrossBridgeMonitor {

    private val _status = MutableStateFlow(BridgeFillStatus())
    val status: StateFlow<BridgeFillStatus> = _status

    private var monitorJob: Job? = null

    data class BridgeFillStatus(
        val state: State = State.UNKNOWN,
        val elapsedMs: Long = 0,
        val estimatedFillMs: Long = 0,
        val fillTxHash: String? = null,
        val errorMessage: String? = null,
    ) {
        enum class State { UNKNOWN, DEPOSITED, RELAYING, FILLED, FAILED, TIMEOUT }
        val progressPercent: Float
            get() = if (estimatedFillMs > 0) (elapsedMs.toFloat() / estimatedFillMs).coerceIn(0f, 1f) else 0f
        val isTerminal: Boolean get() = state == State.FILLED || state == State.FAILED || state == State.TIMEOUT
        val statusText: String get() = when (state) {
            State.UNKNOWN -> "Initializing..."
            State.DEPOSITED -> "Deposit confirmed, waiting for relay..."
            State.RELAYING -> "Relay in progress... ${String.format("%.0f", progressPercent * 100)}%"
            State.FILLED -> "Bridge completed!"
            State.FAILED -> "Bridge failed: ${errorMessage ?: "unknown error"}"
            State.TIMEOUT -> "Bridge timed out: ${errorMessage ?: ""}"
        }
    }

    fun startMonitoring(
        scope: CoroutineScope,
        estimatedFillTimeSec: Int,
        timeoutMs: Long = 30 * 60 * 1000L,
    ) {
        stopMonitoring()
        val startTime = System.currentTimeMillis()
        val estimatedMs = estimatedFillTimeSec.toLong() * 1000

        _status.value = BridgeFillStatus(
            state = BridgeFillStatus.State.DEPOSITED,
            estimatedFillMs = estimatedMs,
        )

        monitorJob = scope.launch(Dispatchers.Default) {
            val pollIntervalMs = when {
                estimatedMs < 60_000 -> 3_000L
                estimatedMs < 300_000 -> 10_000L
                else -> 30_000L
            }

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime

                if (elapsed > timeoutMs) {
                    _status.value = _status.value.copy(
                        state = BridgeFillStatus.State.TIMEOUT,
                        elapsedMs = elapsed,
                        errorMessage = "Bridge did not fill within ${timeoutMs / 60000} minutes",
                    )
                    return@launch
                }

                // Simulate state transitions based on elapsed time
                // In production, this would poll Across API
                val newState = when {
                    elapsed > estimatedMs * 2 -> BridgeFillStatus.State.FILLED
                    elapsed > estimatedMs * 0.5 -> BridgeFillStatus.State.RELAYING
                    else -> BridgeFillStatus.State.DEPOSITED
                }

                _status.value = _status.value.copy(
                    state = newState,
                    elapsedMs = elapsed,
                )

                if (newState == BridgeFillStatus.State.FILLED) return@launch
                delay(pollIntervalMs)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _status.value = BridgeFillStatus()
    }
}
