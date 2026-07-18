package com.tangem.tap.domain.wallets

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.Message
import com.tangem.common.CompletionResult
import com.tangem.common.core.CardSessionRunnable
import com.tangem.common.core.TangemSdkError
import com.tangem.common.extensions.hexToBytes
import com.tangem.domain.wallets.usecase.PurgeWalletError
import com.tangem.domain.wallets.usecase.PurgeWalletUseCase
import com.tangem.operations.wallet.PurgeWalletCommand
import com.tangem.sdk.api.TangemSdkManager

internal class DefaultPurgeWalletUseCase(
    private val tangemSdkManager: TangemSdkManager,
) : PurgeWalletUseCase {

    override suspend fun invoke(walletPublicKey: String): Either<PurgeWalletError, Boolean> {
        val publicKeyBytes = try {
            walletPublicKey.hexToBytes()
        } catch (e: Exception) {
            return PurgeWalletError.SdkError.left()
        }

        val result = tangemSdkManager.runTaskAsync(
            runnable = PurgeWalletRunnable(publicKeyBytes),
            preflightReadFilter = null,
            initialMessage = Message("Remove wallet from card"),
        )

        return when (result) {
            is CompletionResult.Success -> result.data.right()
            is CompletionResult.Failure -> {
                val error = when (result.error) {
                    is TangemSdkError.UserCancelled -> PurgeWalletError.UserCanceled
                    is TangemSdkError.WrongAccessCode -> PurgeWalletError.WrongPin
                    else -> PurgeWalletError.SdkError
                }
                error.left()
            }
        }
    }

    private class PurgeWalletRunnable(
        private val publicKey: ByteArray,
    ) : CardSessionRunnable<Boolean> {

        override val allowsRequestAccessCodeFromRepository: Boolean = false

        override fun run(session: com.tangem.common.core.CardSession, callback: (CompletionResult<Boolean>) -> Unit) {
            PurgeWalletCommand(publicKey).run(session) { result ->
                when (result) {
                    is CompletionResult.Success -> {
                        session.environment.card = session.environment.card?.setWallets(emptyList())
                        callback(CompletionResult.Success(true))
                    }
                    is CompletionResult.Failure -> callback(CompletionResult.Failure(result.error))
                }
            }
        }
    }
}
