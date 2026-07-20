package com.tangem.feature.swap.providers

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.LiFiApi
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.feature.swap.domain.models.SwapAmount
import javax.inject.Inject

class LiFiDexProvider @Inject constructor(
    private val liFiApi: LiFiApi,
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
        return try {
            val fromToken = forLifiToken(fromContractAddress, fromNetwork)
            val toToken = forLifiToken(toContractAddress, toNetwork)
            val fromChain = lifiChainId(fromNetwork)
            val toChain = lifiChainId(toNetwork)

            android.util.Log.d("LiFiDex", "quote: $fromChain/$fromToken -> $toChain/$toToken amount=$fromAmount")

            val resp = liFiApi.getQuote(
                fromChain = fromChain,
                toChain = toChain,
                fromToken = fromToken,
                toToken = toToken,
                fromAmount = fromAmount,
                fromAddress = fromAddress,
            )
            val est = resp.estimate ?: return ExpressDataError.UnknownError().left()
            val amt = est.toAmount ?: return ExpressDataError.UnknownError().left()

            android.util.Log.d("LiFiDex", "result: tool=${resp.tool} toAmount=$amt")

            QuoteModel(
                toTokenAmount = SwapAmount(rawToAmount(amt, toDecimals), toDecimals),
                allowanceContract = est.approvalAddress,
                txType = ExpressTxType.SWAP,
                providerId = providerId,
            ).right()
        } catch (e: Exception) {
            android.util.Log.e("LiFiDex", "quote error: ${e.message}")
            ExpressDataError.UnknownError().left()
        }
    }

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

    /**
     * Convert network name to LI.FI chain ID.
     * LI.FI uses its own IDs for non-EVM chains.
     */
    private fun lifiChainId(network: String): String {
        val normalized = normalizeChain(network)
        return LIFI_CHAIN_IDS[normalized] ?: (CHAIN_IDS[normalized]?.toString() ?: "1")
    }
}
