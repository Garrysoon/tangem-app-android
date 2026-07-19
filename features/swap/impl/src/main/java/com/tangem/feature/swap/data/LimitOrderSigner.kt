package com.tangem.feature.swap.data

import com.tangem.blockchain.blockchains.ethereum.EthereumUtils
import com.tangem.blockchain.common.TransactionSigner
import com.tangem.blockchain.common.Wallet
import com.tangem.common.CompletionResult

/**
 * Signs EIP-712 typed data for limit orders using the wallet's TransactionSigner.
 */
class LimitOrderSigner {

    data class SignedOrder(
        val signature: ByteArray,
        val r: ByteArray,
        val s: ByteArray,
        val v: Int,
    ) {
        val signatureHex: String
            get() = "0x" + signature.joinToString("") { "%02x".format(it) }
    }

    suspend fun sign(
        typedDataJson: String,
        signer: TransactionSigner,
        publicKey: Wallet.PublicKey,
    ): Result<SignedOrder> {
        return try {
            val hash = EthereumUtils.makeTypedDataHash(typedDataJson)
            when (val result = signer.sign(hash, publicKey)) {
                is CompletionResult.Success -> {
                    val sig = result.data
                    if (sig.size < 65) {
                        return Result.failure(IllegalStateException("Signature too short"))
                    }
                    Result.success(
                        SignedOrder(
                            signature = sig,
                            r = sig.sliceArray(0..31),
                            s = sig.sliceArray(32..63),
                            v = sig[64].toInt() and 0xFF,
                        ),
                    )
                }
                is CompletionResult.Failure -> {
                    Result.failure(Exception("Signing failed: ${result.error.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
