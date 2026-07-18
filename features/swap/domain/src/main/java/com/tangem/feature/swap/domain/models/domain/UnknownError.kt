package com.tangem.feature.swap.domain.models.domain

/**
 * Generic unknown error for swap operations.
 * Used in SwapRepository.getExchangeStatus return type.
 */
class UnknownError(message: String? = null) : Exception(message)
