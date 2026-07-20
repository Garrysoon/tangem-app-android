package com.tangem.feature.swap.providers

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.ParaswapApi
import com.tangem.datasource.api.swap.models.ParaswapTxRequest
import com.tangem.datasource.api.swap.models.ParaswapTxResponse
import com.tangem.datasource.api.swap.models.ParaswapPricesResponse
import com.tangem.datasource.api.swap.models.ParaswapPriceRoute
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.feature.swap.domain.models.SwapAmount
import java.math.BigDecimal
import javax.inject.Inject

class ParaswapDexProvider @Inject constructor(private val paraswapApi: ParaswapApi) : DexProvider {
    override val providerId = "paraswap"
    override val name = "Paraswap"
    override fun isSupported(fromNetwork: String, toNetwork: String): Boolean =
        fromNetwork.lowercase() == toNetwork.lowercase() && !isUtxoChain(fromNetwork) && CHAIN_IDS.containsKey(normalizeChain(fromNetwork))

    override suspend fun getQuote(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String?): Either<ExpressDataError, QuoteModel> {
        return try {
            val from = safeTokenAddr(fromContractAddress); val to = safeTokenAddr(toContractAddress)
            val resp = paraswapApi.getPrices(from, to, fromAmount, fromDecimals, toDecimals, network = chainToId(fromNetwork))
            val route = resp.priceRoute ?: return ExpressDataError.UnknownError().left()
            QuoteModel(toTokenAmount = SwapAmount(rawToAmount(route.destAmount, toDecimals), toDecimals), allowanceContract = route.tokenTransferProxy, txType = ExpressTxType.SWAP, providerId = providerId).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }

    override suspend fun buildTx(userWallet: UserWallet, fromContractAddress: String, fromNetwork: String, toContractAddress: String, toNetwork: String, fromAmount: String, fromDecimals: Int, toDecimals: Int, fromAddress: String, toAddress: String): Either<ExpressDataError, SwapDataModel> {
        return try {
            val from = safeTokenAddr(fromContractAddress); val to = safeTokenAddr(toContractAddress); val cid = chainToId(fromNetwork)
            val pr = paraswapApi.getPrices(from, to, fromAmount, fromDecimals, toDecimals, network = cid).priceRoute ?: return ExpressDataError.UnknownError().left()
            val minOut = (pr.destAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO).multiply(BigDecimal("0.99")).toBigInteger().toString()
            val tx = paraswapApi.buildTransaction(chainId = cid, body = ParaswapTxRequest(from, to, fromAmount, minOut, pr, fromAddress, toAddress, fromDecimals, toDecimals))
            val fromAmt = SwapAmount(rawToAmount(fromAmount, fromDecimals), fromDecimals)
            val toAmt = SwapAmount(rawToAmount("0", toDecimals), toDecimals)
            SwapDataModel(toTokenAmount = toAmt, transaction = ExpressTransactionModel.DEX(fromAmount = fromAmt, toAmount = toAmt, txValue = tx.value ?: "0", txId = "", txTo = tx.to ?: "", txExtraId = null, txFrom = fromAddress, txData = tx.data ?: "", otherNativeFeeWei = null, gas = tx.gas?.toBigInteger(), allowanceContract = pr.tokenTransferProxy)).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }
}