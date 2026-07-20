package com.tangem.feature.swap.providers

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.CoWSwapApi
import com.tangem.datasource.api.swap.CoWQuoteRequest
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.feature.swap.domain.models.SwapAmount
import javax.inject.Inject

class CoWSwapDexProvider @Inject constructor(private val coWSwapApi: CoWSwapApi) : DexProvider {
    override val providerId = "cowswap"
    override val name = "CoW Swap"
    private val NETWORKS = mapOf("ethereum" to "mainnet", "arbitrum" to "arbitrum", "gnosis" to "xdai")
    override fun isSupported(fromNetwork: String, toNetwork: String): Boolean =
        NETWORKS.containsKey(normalizeChain(fromNetwork)) && fromNetwork.lowercase() == toNetwork.lowercase()

    override suspend fun getQuote(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String?): Either<ExpressDataError, QuoteModel> {
        return try {
            val cowNet = NETWORKS[normalizeChain(fromNetwork)] ?: return ExpressDataError.UnknownError().left()
            val resp = coWSwapApi.getQuote(cowNet, CoWQuoteRequest(sellToken = safeTokenAddr(fromContractAddress), buyToken = safeTokenAddr(toContractAddress), sellAmount = fromAmount, kind = "sell", from = null))
            val buy = resp.quote?.buyAmount ?: return ExpressDataError.UnknownError().left()
            QuoteModel(toTokenAmount = SwapAmount(rawToAmount(buy, toDecimals), toDecimals), allowanceContract = null, txType = ExpressTxType.SWAP, providerId = providerId).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }

    override suspend fun buildTx(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String, toAddress: String): Either<ExpressDataError, SwapDataModel> =
        ExpressDataError.UnknownError().left()
}