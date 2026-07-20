package com.tangem.feature.swap.providers

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.OdosApi
import com.tangem.datasource.api.swap.OdosQuoteRequest
import com.tangem.datasource.api.swap.OdosTokenAmount
import com.tangem.datasource.api.swap.OdosTokenProportion
import com.tangem.datasource.api.swap.OdosAssembleRequest
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.feature.swap.domain.models.SwapAmount
import javax.inject.Inject

class OdosDexProvider @Inject constructor(private val odosApi: OdosApi) : DexProvider {
    override val providerId = "odos"
    override val name = "Odos"
    override fun isSupported(fromNetwork: String, toNetwork: String): Boolean =
        fromNetwork.lowercase() == toNetwork.lowercase() && CHAIN_IDS.containsKey(normalizeChain(fromNetwork))

    override suspend fun getQuote(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String?): Either<ExpressDataError, QuoteModel> {
        return try {
            val cid = CHAIN_IDS[normalizeChain(fromNetwork)] ?: return ExpressDataError.UnknownError().left()
            val resp = odosApi.getQuote(OdosQuoteRequest(chainId = cid, inputTokens = listOf(OdosTokenAmount(safeTokenAddr(fromContractAddress), fromAmount)), outputTokens = listOf(OdosTokenProportion(safeTokenAddr(toContractAddress), 1f)), slippageLimitPercent = 0.5f, userAddr = null))
            val out = resp.outAmounts?.firstOrNull() ?: return ExpressDataError.UnknownError().left()
            QuoteModel(toTokenAmount = SwapAmount(rawToAmount(out, toDecimals), toDecimals), allowanceContract = null, txType = ExpressTxType.SWAP, providerId = providerId).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }

    override suspend fun buildTx(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String, toAddress: String): Either<ExpressDataError, SwapDataModel> {
        return try {
            val cid = CHAIN_IDS[normalizeChain(fromNetwork)] ?: return ExpressDataError.UnknownError().left()
            val qr = odosApi.getQuote(OdosQuoteRequest(chainId = cid, inputTokens = listOf(OdosTokenAmount(safeTokenAddr(fromContractAddress), fromAmount)), outputTokens = listOf(OdosTokenProportion(safeTokenAddr(toContractAddress), 1f)), slippageLimitPercent = 0.5f, userAddr = fromAddress))
            val pathId = qr.pathId ?: return ExpressDataError.UnknownError().left()
            val ar = odosApi.assembleTransaction(OdosAssembleRequest(userAddr = fromAddress, pathId = pathId, simulate = false))
            val tx = ar.transaction ?: return ExpressDataError.UnknownError().left()
            val out = ar.outAmounts?.firstOrNull() ?: "0"
            val fromAmt = SwapAmount(rawToAmount(fromAmount, fromDecimals), fromDecimals)
            val toAmt = SwapAmount(rawToAmount(out, toDecimals), toDecimals)
            SwapDataModel(toTokenAmount = toAmt, transaction = ExpressTransactionModel.DEX(fromAmount = fromAmt, toAmount = toAmt, txValue = tx.value ?: "0", txId = "", txTo = tx.to ?: "", txExtraId = null, txFrom = fromAddress, txData = tx.data ?: "", otherNativeFeeWei = null, gas = tx.gas?.toBigInteger(), allowanceContract = null)).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }
}