package com.tangem.data.swap

import com.tangem.datasource.api.swap.KyberSwapApi
import com.tangem.datasource.api.swap.ParaswapApi
import com.tangem.datasource.api.swap.models.KyberBuildRequest
import com.tangem.datasource.api.swap.models.ParaswapTxRequest
import com.tangem.domain.express.models.ExpressOperationType
import com.tangem.domain.express.models.ExpressProvider
import com.tangem.domain.express.models.ExpressProviderType
import com.tangem.domain.express.models.ExpressRateType
import com.tangem.domain.models.currency.CryptoCurrency
import com.tangem.domain.models.currency.CryptoCurrencyStatus
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.swap.SwapRepositoryV2
import com.tangem.domain.swap.models.*
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger
import javax.inject.Inject

internal class RaksaSwapRepositoryV2 @Inject constructor(
    private val paraswapApi: ParaswapApi,
    private val kyberSwapApi: KyberSwapApi,
    private val coroutineDispatcher: CoroutineDispatcherProvider,
) : SwapRepositoryV2 {

    companion object {
        private const val NATIVE = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
        private val CHAIN_IDS = mapOf(
            "ethereum" to 1, "arbitrum" to 42161, "optimism" to 10,
            "base" to 8453, "polygon" to 137, "bsc" to 56
        )
        private val KYBER_SLUGS = mapOf(
            "ethereum" to "ethereum", "arbitrum" to "arbitrum", "optimism" to "optimism",
            "base" to "base", "polygon" to "polygon", "bsc" to "bsc"
        )
    }

    private fun dexProvider(id: String, name: String) = ExpressProvider(
        providerId = id, name = name, type = ExpressProviderType.DEX,
        imageLarge = "", termsOfUse = null, privacyPolicy = null,
        slippage = null, rateTypes = listOf(ExpressRateType.Float),
    )
    private val providers = listOf(dexProvider("paraswap", "Paraswap"), dexProvider("kyberswap", "KyberSwap"))

    override suspend fun getPairs(
        primarySwapCurrencyStatus: SwapCurrencyStatus,
        secondarySwapCurrencyStatus: SwapCurrencyStatus,
        filterProviderTypes: List<ExpressProviderType>,
        swapTxType: SwapTxType,
    ): List<SwapPairModel> = withContext(coroutineDispatcher.default) {
        val p = providers.filter { it.type in filterProviderTypes }
        listOf(SwapPairModel(
            from = primarySwapCurrencyStatus.status,
            to = secondarySwapCurrencyStatus.status,
            providers = p,
        ))
    }

    override suspend fun getPairs(
        userWallet: UserWallet,
        initialCurrency: CryptoCurrency,
        cryptoCurrencyStatusList: List<CryptoCurrencyStatus>,
        filterProviderTypes: List<ExpressProviderType>,
        swapTxType: SwapTxType,
    ): List<SwapPairModel> = emptyList()

    override suspend fun getSupportedPairs(
        userWallet: UserWallet,
        initialCurrency: CryptoCurrency,
        cryptoCurrencyList: List<CryptoCurrency>,
        filterProviderTypes: List<ExpressProviderType>,
        swapTxType: SwapTxType,
    ): List<SwapPairModel> = emptyList()

    override suspend fun getSwapQuote(
        userWallet: UserWallet,
        fromCryptoCurrency: CryptoCurrency,
        toCryptoCurrency: CryptoCurrency,
        amount: BigDecimal,
        amountType: SwapAmountType,
        provider: ExpressProvider,
        rateType: ExpressRateType,
    ): SwapQuoteModel = withContext(coroutineDispatcher.default) {
        val fromAddr = addr(fromCryptoCurrency)
        val toAddr = addr(toCryptoCurrency)
        val fromDec = fromCryptoCurrency.decimals
        val toDec = toCryptoCurrency.decimals
        val chain = chainOf(fromCryptoCurrency)
        val raw = amount.movePointRight(fromDec).toBigInteger().toString()
        val amountOut = when (provider.providerId) {
            "paraswap" -> {
                val r = paraswapApi.getPrices(fromAddr, toAddr, raw, fromDec, toDec, network = chainId(chain))
                val route = r.priceRoute ?: throw RuntimeException(r.error ?: "paraswap")
                route.destAmount.toBigDecimal().movePointLeft(toDec)
            }
            "kyberswap" -> {
                val slug = KYBER_SLUGS[chain] ?: throw RuntimeException("kyber: $chain")
                val r = kyberSwapApi.getRoutes(slug, fromAddr, toAddr, raw)
                if (r.code != 0) throw RuntimeException(r.message ?: "kyber")
                val s = r.data?.routeSummary ?: throw RuntimeException("kyber: no route")
                s.amountOut.toBigDecimal().movePointLeft(toDec)
            }
            else -> throw RuntimeException("Unsupported: ${provider.providerId}")
        }
        SwapQuoteModel(provider = provider, toTokenAmount = amountOut, fromTokenAmount = null, allowanceContract = null)
    }

    override suspend fun getSwapData(
        userWallet: UserWallet,
        fromCryptoCurrencyStatus: CryptoCurrencyStatus,
        toCryptoCurrency: CryptoCurrency,
        amount: BigDecimal,
        amountType: SwapAmountType,
        toAddress: String,
        toExtraId: String?,
        expressProvider: ExpressProvider,
        rateType: ExpressRateType,
        expressOperationType: ExpressOperationType,
        quoteId: String?,
    ): SwapDataModel = withContext(coroutineDispatcher.default) {
        val from = fromCryptoCurrencyStatus.currency
        val fromAddr = addr(from)
        val toAddr = addr(toCryptoCurrency)
        val chain = chainOf(from)
        val fromDec = from.decimals
        val toDec = toCryptoCurrency.decimals
        val raw = amount.movePointRight(fromDec).toBigInteger().toString()
        val sender = userWallet.walletId.stringValue

        val (txTo, txData, txValue, gas, allowance) = when (expressProvider.providerId) {
            "paraswap" -> {
                val prices = paraswapApi.getPrices(fromAddr, toAddr, raw, fromDec, toDec, network = chainId(chain))
                val route = prices.priceRoute ?: throw RuntimeException(prices.error ?: "paraswap")
                val minOut = (route.destAmount.toBigDecimal() * BigDecimal("0.99")).toBigInteger().toString()
                val tx = paraswapApi.buildTransaction(chainId(chain), body = ParaswapTxRequest(
                    srcToken = fromAddr, destToken = toAddr, srcAmount = raw, destAmount = minOut,
                    priceRoute = route, userAddress = sender, receiver = toAddress,
                    srcDecimals = fromDec, destDecimals = toDec))
                if (tx.error != null) throw RuntimeException(tx.error)
                SwapTxData(tx.to, tx.data, tx.value ?: "0", tx.gas?.toBigIntegerOrNull() ?: BigInteger.ZERO, route.tokenTransferProxy ?: tx.to)
            }
            "kyberswap" -> {
                val slug = KYBER_SLUGS[chain] ?: throw RuntimeException("kyber: $chain")
                val routes = kyberSwapApi.getRoutes(slug, fromAddr, toAddr, raw)
                if (routes.code != 0) throw RuntimeException(routes.message ?: "kyber")
                val summary = routes.data?.routeSummary ?: throw RuntimeException("kyber: no route")
                val br = kyberSwapApi.buildRoute(slug, KyberBuildRequest(
                    routeSummary = summary, sender = sender, recipient = toAddress, slippageTolerance = 100))
                if (br.code != 0) throw RuntimeException(br.message ?: "kyber build")
                val bd = br.data ?: throw RuntimeException("kyber: no data")
                val isNative = fromAddr.lowercase() == NATIVE.lowercase()
                SwapTxData(bd.routerAddress, bd.data, if (isNative) raw else "0", bd.gas?.toBigIntegerOrNull() ?: BigInteger.ZERO, bd.routerAddress)
            }
            else -> throw RuntimeException("Unsupported: ${expressProvider.providerId}")
        }

        val amountOut = when (expressProvider.providerId) {
            "paraswap" -> {
                val r = paraswapApi.getPrices(fromAddr, toAddr, raw, fromDec, toDec, network = chainId(chain))
                r.priceRoute?.destAmount?.toBigDecimal()?.movePointLeft(toDec) ?: BigDecimal.ZERO
            }
            "kyberswap" -> {
                val slug = KYBER_SLUGS[chain] ?: throw RuntimeException("kyber: $chain")
                val r = kyberSwapApi.getRoutes(slug, fromAddr, toAddr, raw)
                r.data?.routeSummary?.amountOut?.toBigDecimal()?.movePointLeft(toDec) ?: BigDecimal.ZERO
            }
            else -> BigDecimal.ZERO
        }
        val fromAmount = amount.movePointRight(fromDec)

        SwapDataModel(
            toTokenAmount = amountOut,
            transaction = SwapDataTransactionModel.DEX(
                fromAmount = fromAmount, toAmount = amountOut,
                txValue = txValue.toString(), txId = "", txTo = txTo, txExtraId = null,
                txFrom = sender, txData = txData,
                otherNativeFeeWei = null, gas = gas,
            ),
        )
    }

    override suspend fun swapTransactionSent(
        userWallet: UserWallet, fromCryptoCurrencyStatus: CryptoCurrencyStatus,
        payInAddress: String, txId: String, txHash: String, txExtraId: String?,
    ) { /* DEX: on-chain, no backend */ }

    override suspend fun getExchangeStatus(userWallet: UserWallet, txId: String): SwapStatusModel {
        return SwapStatusModel(providerId = "dex", status = SwapStatus.Finished)
    }

    private fun addr(c: CryptoCurrency): String = when (c) {
        is CryptoCurrency.Token -> c.contractAddress
        is CryptoCurrency.Coin -> NATIVE
    }

    private fun chainOf(c: CryptoCurrency): String {
        val id = c.network.rawId.lowercase()
        return when {
            "arbitrum" in id -> "arbitrum"
            "optimism" in id -> "optimism"
            "base" in id -> "base"
            "polygon" in id || "matic" in id -> "polygon"
            "bsc" in id || "bnb" in id -> "bsc"
            "ethereum" in id || id == "1" -> "ethereum"
            else -> "ethereum"
        }
    }

    private data class SwapTxData(
        val to: String, val data: String, val value: String, val gas: BigInteger, val allowance: String,
    )

    private fun chainId(chain: String): Int = CHAIN_IDS[chain.lowercase()] ?: 1
}
