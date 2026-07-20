package com.tangem.feature.swap.providers

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.KyberSwapApi
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.feature.swap.domain.models.SwapAmount
import javax.inject.Inject

class KyberSwapDexProvider @Inject constructor(
    private val kyberSwapApi: KyberSwapApi,
) : DexProvider {
    override val providerId = "kyberswap"
    override val name = "KyberSwap"
    private val SLUGS = mapOf("ethereum" to "eth", "arbitrum" to "arbitrum", "optimism" to "optimism", "base" to "base", "polygon" to "polygon", "bsc" to "bsc", "avalanche" to "avax")

    override fun isSupported(fromNetwork: String, toNetwork: String): Boolean =
        SLUGS.containsKey(normalizeChain(fromNetwork)) && fromNetwork.lowercase() == toNetwork.lowercase()

    override suspend fun getQuote(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String?): Either<ExpressDataError, QuoteModel> {
        return try {
            val slug = SLUGS[normalizeChain(fromNetwork)] ?: return ExpressDataError.UnknownError().left()
            val resp = kyberSwapApi.getRoutes(slug, safeTokenAddr(fromContractAddress), safeTokenAddr(toContractAddress), fromAmount)
            if (resp.code != 0) return ExpressDataError.UnknownError().left()
            val summary = resp.data?.routeSummary ?: return ExpressDataError.UnknownError().left()
            QuoteModel(toTokenAmount = SwapAmount(rawToAmount(summary.amountOut, toDecimals), toDecimals), allowanceContract = null, txType = ExpressTxType.SWAP, providerId = providerId).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }

    override suspend fun buildTx(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String, toAddress: String): Either<ExpressDataError, SwapDataModel> =
        ExpressDataError.UnknownError().left()
}