package com.tangem.feature.swap.providers

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.LiFiApi
import com.tangem.datasource.api.swap.LiFiIntentsApi
import com.tangem.datasource.api.swap.LiFiIntentsQuoteRequest
import com.tangem.datasource.api.swap.LiFiIntentsUser
import com.tangem.datasource.api.swap.LiFiIntentsIntent
import com.tangem.datasource.api.swap.LiFiIntentsAsset
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.feature.swap.domain.models.SwapAmount
import javax.inject.Inject

class LiFiDexProvider @Inject constructor(
    private val liFiApi: LiFiApi,
    private val liFiIntentsApi: LiFiIntentsApi,
) : DexProvider {
    override val providerId = "lifi"
    override val name = "LI.FI"

    override fun isSupported(fromNetwork: String, toNetwork: String) = true

    override suspend fun getQuote(
        userWallet: UserWallet,
        fromContractAddress: String,
        fromNetwork: String,
        toContractAddress: String,
        toNetwork: String,
        fromAmount: String,
        fromDecimals: Int,
        toDecimals: Int,
        fromAddress: String?,
    ): Either<ExpressDataError, QuoteModel> {
        if (fromAddress.isNullOrBlank()) {
            android.util.Log.d("LiFiDex", "fromAddress is null, skipping")
            return ExpressDataError.UnknownError().left()
        }

        // TRON: use Intents API (base58 addresses)
        val isTronFrom = normalizeChain(fromNetwork) == "tron"
        val isTronTo = normalizeChain(toNetwork) == "tron"

        return if (isTronFrom || isTronTo) {
            getTronQuote(fromContractAddress, fromNetwork, toContractAddress, toNetwork, fromAmount, fromAddress, toDecimals)
        } else {
            getEvmQuote(fromContractAddress, fromNetwork, toContractAddress, toNetwork, fromAmount, fromAddress, toDecimals)
        }
    }

    /**
     * Standard LI.FI /v1/quote for EVM and Bitcoin chains.
     */
    private suspend fun getEvmQuote(
        fromContractAddress: String, fromNetwork: String,
        toContractAddress: String, toNetwork: String,
        fromAmount: String, fromAddress: String, toDecimals: Int,
    ): Either<ExpressDataError, QuoteModel> {
        return try {
            val fromToken = forLifiToken(fromContractAddress, fromNetwork)
            val toToken = forLifiToken(toContractAddress, toNetwork)
            val fromChain = lifiChainId(fromNetwork)
            val toChain = lifiChainId(toNetwork)

            android.util.Log.d("LiFiDex", "evm quote: $fromChain/$fromToken -> $toChain/$toToken amount=$fromAmount")

            val resp = liFiApi.getQuote(
                fromChain = fromChain, toChain = toChain,
                fromToken = fromToken, toToken = toToken,
                fromAmount = fromAmount, fromAddress = fromAddress,
            )
            val est = resp.estimate ?: return ExpressDataError.UnknownError().left()
            val amt = est.toAmount ?: return ExpressDataError.UnknownError().left()

            android.util.Log.d("LiFiDex", "evm result: tool=${resp.tool} toAmount=$amt")

            QuoteModel(
                toTokenAmount = SwapAmount(rawToAmount(amt, toDecimals), toDecimals),
                allowanceContract = est.approvalAddress,
                txType = ExpressTxType.SWAP,
                providerId = providerId,
            ).right()
        } catch (e: Exception) {
            android.util.Log.e("LiFiDex", "evm quote error: ${e.message}")
            ExpressDataError.UnknownError().left()
        }
    }

    /**
     * LI.FI Intents API for TRON chains (base58 addresses, CAIP-2 format).
     * Uses POST /api/v1/integrator/quote/request on order.li.fi
     */
    private suspend fun getTronQuote(
        fromContractAddress: String, fromNetwork: String,
        toContractAddress: String, toNetwork: String,
        fromAmount: String, fromAddress: String, toDecimals: Int,
    ): Either<ExpressDataError, QuoteModel> {
        return try {
            val fromChainCaip = "tron:728126428"
            val toChainCaip = if (normalizeChain(toNetwork) == "tron") "tron:728126428"
                else "eip155:${lifiChainId(toNetwork)}"

            val fromAsset = forTronAsset(fromContractAddress)
            val toAsset = forTronAsset(toContractAddress)

            android.util.Log.d("LiFiDex", "tron quote: $fromChainCaip/$fromAsset -> $toChainCaip/$toAsset amount=$fromAmount")

            val request = LiFiIntentsQuoteRequest(
                user = LiFiIntentsUser(chain = fromChainCaip, address = fromAddress),
                intent = LiFiIntentsIntent(
                    inputs = listOf(LiFiIntentsAsset(chain = fromChainCaip, user = fromAddress, asset = fromAsset, amount = fromAmount)),
                    outputs = listOf(LiFiIntentsAsset(chain = toChainCaip, receiver = fromAddress, asset = toAsset, amount = null)),
                ),
            )

            val resp = liFiIntentsApi.requestQuote(request)
            val quotes = resp.quotes
            if (quotes.isNullOrEmpty()) {
                android.util.Log.d("LiFiDex", "tron: no quotes available for $fromChainCaip/$fromAsset -> $toChainCaip/$toAsset")
                return ExpressDataError.UnknownError().left()
            }

            val bestQuote = quotes.first()
            val toAmount = bestQuote.preview?.outputs?.firstOrNull()?.amount

            android.util.Log.d("LiFiDex", "tron result: solver=${bestQuote.source?.solver} toAmount=$toAmount")

            if (toAmount != null) {
                QuoteModel(
                    toTokenAmount = SwapAmount(rawToAmount(toAmount, toDecimals), toDecimals),
                    allowanceContract = null,
                    txType = ExpressTxType.SWAP,
                    providerId = providerId,
                ).right()
            } else {
                android.util.Log.d("LiFiDex", "tron: quote found but no output amount")
                ExpressDataError.UnknownError().left()
            }
        } catch (e: Exception) {
            android.util.Log.e("LiFiDex", "tron quote error: ${e.message}")
            ExpressDataError.UnknownError().left()
        }
    }

    private fun lifiChainId(network: String): String {
        val normalized = normalizeChain(network)
        return LIFI_CHAIN_IDS[normalized] ?: (CHAIN_IDS[normalized]?.toString() ?: "1")
    }

    /**
     * For TRON native token, use the real TRX address from LI.FI token list.
     */
    private fun forTronAsset(addr: String?): String {
        if (isNativeToken(addr)) return "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb"
        return addr ?: ""
    }

    /**
     * LI.FI combines quote + tx in /v1/quote response (transactionRequest).
     * For Intents API (TRON), tx is in order.openIntentTx.
     * Build tx is handled by the swap execution layer using these fields.
     */
    override suspend fun buildTx(
        userWallet: UserWallet,
        fromContractAddress: String,
        fromNetwork: String,
        toContractAddress: String,
        toNetwork: String,
        fromAmount: String,
        fromDecimals: Int,
        toDecimals: Int,
        fromAddress: String,
        toAddress: String,
    ): Either<ExpressDataError, SwapDataModel> = ExpressDataError.UnknownError().left()
}
