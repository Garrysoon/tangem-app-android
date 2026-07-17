package com.tangem.domain.wallets.usecase

import arrow.core.Either

/**
 * Use case for purging a wallet from the SecurityOS card.
 * Requires NFC card scan and PIN verification.
 */
interface PurgeWalletUseCase {

    /**
     * Purges wallet with provided public key from the card.
     *
     * @param walletPublicKey public key of the wallet to purge (hex string)
     *
     * @return [Either] with [PurgeWalletError] or [Boolean] indicating success
     */
    suspend operator fun invoke(walletPublicKey: String): Either<PurgeWalletError, Boolean>
}

sealed class PurgeWalletError {
    data object UserCanceled : PurgeWalletError()
    data object SdkError : PurgeWalletError()
    data object WrongPin : PurgeWalletError()
}
