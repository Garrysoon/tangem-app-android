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

    override suspend fun getQuote(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String?): Either<ExpressDataError, QuoteModel> {
        if (fromAddress.isNullOrBlank()) return ExpressDataError.UnknownError().left()
        return try {
            val fromUtxo = isUtxoChain(fromNetwork); val toUtxo = isUtxoChain(toNetwork)
            val resp = liFiApi.getQuote(fromChain = normalizeChain(fromNetwork), toChain = normalizeChain(toNetwork), fromToken = safeTokenAddr(fromContractAddress, fromUtxo), toToken = safeTokenAddr(toContractAddress, toUtxo), fromAmount = fromAmount, fromAddress = fromAddress)
            val est = resp.estimate ?: return ExpressDataError.UnknownError().left()
            val amt = est.toAmount ?: return ExpressDataError.UnknownError().left()
            QuoteModel(toTokenAmount = SwapAmount(rawToAmount(amt, toDecimals), toDecimals), allowanceContract = est.approvalAddress, txType = ExpressTxType.SWAP, providerId = providerId).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }

    override suspend fun buildTx(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String, toAddress: String): Either<ExpressDataError, SwapDataModel> =
        ExpressDataError.UnknownError().left()
}