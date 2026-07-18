package com.tangem.feature.swap

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.express.models.ExpressOperationType
import com.tangem.domain.models.currency.CryptoCurrency
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.datasource.api.swap.KyberSwapApi
import com.tangem.datasource.api.swap.ParaswapApi
import com.tangem.datasource.api.swap.models.KyberBuildRequest
import com.tangem.datasource.api.swap.models.KyberRouteSummary
import com.tangem.datasource.api.swap.models.ParaswapTxRequest
import com.tangem.feature.swap.DexTokenList
import com.tangem.feature.swap.domain.api.SwapRepository
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.SwapAmount
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import javax.inject.Inject

/**
 * SwapRepository that calls DEX APIs directly (Paraswap + KyberSwap).
 * No intermediate backend required.
 */
internal class RaksaSwapRepository @Inject constructor(
    private val paraswapApi: ParaswapApi,
    private val kyberSwapApi: KyberSwapApi,
    private val coroutineDispatcher: CoroutineDispatcherProvider,
) : SwapRepository {

    override suspend fun getPairs(
        userWallet: UserWallet,
        initialCurrency: LeastTokenInfo,
        currencyList: List<CryptoCurrency>,
    ): PairsWithProviders = getPairsOnly(userWallet, initialCurrency, currencyList)

    override suspend fun getPairsOnly(
        userWallet: UserWallet,
        initialCurrency: LeastTokenInfo,
        currencyList: List<CryptoCurrency>,
        isIgnoreExpress: Boolean,
    ): PairsWithProviders = withContext(coroutineDispatcher.io) {
        val providers = listOf(
            createDexProvider("paraswap", "Paraswap"),
            createDexProvider("kyberswap", "KyberSwap"),
        )
        // Build local pairs from currencyList
        val pairs = currencyList.mapNotNull { currency ->
            val leastToken = currency.toLeastTokenInfo() ?: return@mapNotNull null
            SwapPairLeast(
                from = initialCurrency,
                to = leastToken,
                providers = providers,
            )
        }
        PairsWithProviders(pairs = pairs, allProviders = providers)
    }

    override suspend fun findBestQuote(
        userWallet: UserWallet,
        fromContractAddress: String,
        fromNetwork: String,
        toContractAddress: String,
        toNetwork: String,
        fromAmount: String,
        fromDecimals: Int,
        toDecimals: Int,
        providerId: String,
        rateType: RateType,
    ): Either<ExpressDataError, QuoteModel> = withContext(coroutineDispatcher.io) {
        // Check if both tokens are on the same supported chain
        if (!DexTokenList.isChainSupported(fromNetwork) || !DexTokenList.isChainSupported(toNetwork)) {
            return@withContext ExpressDataError.UnknownError().left()
        }
        // Cross-chain not supported by DEX
        if (fromNetwork.lowercase() != toNetwork.lowercase()) {
            return@withContext ExpressDataError.UnknownError().left()
        }
        try {
            val rawAmount = toRawAmount(fromAmount, fromDecimals)
            val results = mutableListOf<DexResult>()

            // Paraswap
            try {
                val resp = paraswapApi.getPrices(
                    srcToken = fromContractAddress,
                    destToken = toContractAddress,
                    amount = rawAmount,
                    srcDecimals = fromDecimals,
                    destDecimals = toDecimals,
                    network = toChainId(fromNetwork),
                )
                val route = resp.priceRoute
                if (route != null) {
                    results.add(DexResult(
                        source = "paraswap",
                        amountOutRaw = route.destAmount,
                        gas = null,
                        allowanceContract = route.tokenTransferProxy,
                    ))
                }
            } catch (_: Exception) {}

            // KyberSwap
            try {
                val slug = DexTokenList.kyberChainSlugs[fromNetwork.lowercase()] ?: return@withContext ExpressDataError.UnknownError().left()
                val resp = kyberSwapApi.getRoutes(slug, fromContractAddress, toContractAddress, rawAmount)
                val summary = resp.data?.routeSummary
                if (resp.code == 0 && summary != null) {
                    results.add(DexResult(
                        source = "kyberswap",
                        amountOutRaw = summary.amountOut,
                        gas = summary.gas?.toLongOrNull(),
                        allowanceContract = null,
                    ))
                }
            } catch (_: Exception) {}

            if (results.isEmpty()) {
                return@withContext ExpressDataError.UnknownError().left()
            }

            val best = results.maxByOrNull { it.amountOutRaw.toBigDecimalOrNull() ?: BigDecimal.ZERO }
                ?: results.first()
            val amountOut = rawToAmount(best.amountOutRaw, toDecimals)

            QuoteModel(
                toTokenAmount = SwapAmount(amountOut, toDecimals),
                allowanceContract = best.allowanceContract,
                txType = ExpressTxType.SWAP,
            ).right()
        } catch (e: Exception) {
            ExpressDataError.UnknownError().left()
        }
    }
    override suspend fun getExchangeData(
        userWallet: UserWallet,
        fromContractAddress: String,
        fromNetwork: String,
        toContractAddress: String,
        fromAddress: String,
        toNetwork: String,
        fromAmount: String,
        fromDecimals: Int,
        toDecimals: Int,
        providerId: String,
        rateType: RateType,
        toAddress: String,
        expressOperationType: ExpressOperationType,
        refundAddress: String?,
        refundExtraId: String?,
        toExtraId: String?,
    ): Either<ExpressDataError, SwapDataModel> = withContext(coroutineDispatcher.io) {
        // Check chain support
        if (!DexTokenList.isChainSupported(fromNetwork) || !DexTokenList.isChainSupported(toNetwork)) {
            return@withContext ExpressDataError.UnknownError().left()
        }
        if (fromNetwork.lowercase() != toNetwork.lowercase()) {
            return@withContext ExpressDataError.UnknownError().left()
        }
        try {
            val rawAmount = toRawAmount(fromAmount, fromDecimals)
            val tx = when (providerId.lowercase()) {
                "paraswap" -> buildParaswapTx(fromContractAddress, toContractAddress, rawAmount, fromDecimals, toDecimals, fromNetwork, fromAddress, toAddress)
                "kyberswap" -> buildKyberTx(fromContractAddress, toContractAddress, rawAmount, fromNetwork, fromAddress, toAddress)
                else -> throw RuntimeException("Unsupported DEX: $providerId")
            }
            val amountOut = rawToAmount(tx.amountOutRaw, toDecimals)
            val model = SwapDataModel(
                toTokenAmount = SwapAmount(amountOut, toDecimals),
                transaction = ExpressTransactionModel.DEX(
                    fromAmount = SwapAmount(fromAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO, fromDecimals),
                    toAmount = SwapAmount(amountOut, toDecimals),
                    txValue = tx.value,
                    txId = "",
                    txTo = tx.to,
                    txExtraId = null,
                    txFrom = fromAddress,
                    txData = tx.data,
                    otherNativeFeeWei = null,
                    gas = tx.gas?.toBigInteger(),
                    allowanceContract = tx.allowanceTarget,
                ),
            )
            model.right()
        } catch (e: Exception) {
            ExpressDataError.UnknownError().left()
        }
    }

    override suspend fun getExchangeStatus(
        userWallet: UserWallet?,
        userWalletId: UserWalletId,
        txId: String,
    ): Either<UnknownError, ExchangeStatusModel> = UnknownError().left()

    override suspend fun exchangeSent(
        userWallet: UserWallet,
        txId: String,
        fromNetwork: String,
        fromAddress: String,
        payInAddress: String,
        txHash: String,
        payInExtraId: String?,
    ): Either<ExpressDataError, Unit> = Unit.right()

    override suspend fun getStoredSwapUiMode(): SwapUIMode? = null
    override suspend fun storeSwapUiMode(mode: SwapUIMode) {}

    // ===== Helpers =====

    private data class DexResult(
        val source: String,
        val amountOutRaw: String,
        val gas: Long?,
        val allowanceContract: String?,
    )

    private data class DexTx(
        val to: String,
        val data: String,
        val value: String,
        val gas: String?,
        val amountOutRaw: String,
        val allowanceTarget: String,
    )

    private suspend fun buildParaswapTx(
        sellToken: String, buyToken: String, amount: String,
        sellDecimals: Int, buyDecimals: Int,
        chain: String, sender: String, recipient: String,
    ): DexTx {
        val prices = paraswapApi.getPrices(
            srcToken = sellToken, destToken = buyToken,
            amount = amount, srcDecimals = sellDecimals, destDecimals = buyDecimals,
            network = toChainId(chain),
        )
        val route = prices.priceRoute ?: throw RuntimeException(prices.error ?: "paraswap error")
        val minOut = (route.destAmount.toBigDecimal() * BigDecimal("0.99")).toBigInteger().toString()
        val txResp = paraswapApi.buildTransaction(
            chainId = toChainId(chain),
            body = ParaswapTxRequest(
                srcToken = sellToken, destToken = buyToken,
                srcAmount = amount, destAmount = minOut,
                priceRoute = route,
                userAddress = sender, receiver = recipient,
                srcDecimals = sellDecimals, destDecimals = buyDecimals,
            ),
        )
        if (txResp.error != null) throw RuntimeException(txResp.error)
        return DexTx(
            to = txResp.to,
            data = txResp.data,
            value = txResp.value ?: "0",
            gas = txResp.gas,
            amountOutRaw = route.destAmount,
            allowanceTarget = route.tokenTransferProxy ?: txResp.to,
        )
    }

    private suspend fun buildKyberTx(
        sellToken: String, buyToken: String, amount: String,
        chain: String, sender: String, recipient: String,
    ): DexTx {
        val slug = DexTokenList.kyberChainSlugs[chain.lowercase()]
            ?: throw RuntimeException("kyber: unknown chain $chain")
        val routes = kyberSwapApi.getRoutes(slug, sellToken, buyToken, amount)
        if (routes.code != 0) throw RuntimeException(routes.message ?: "kyber routes error")
        val summary = routes.data?.routeSummary
            ?: throw RuntimeException("kyber: missing routeSummary")
        val buildResp = kyberSwapApi.buildRoute(slug, KyberBuildRequest(
            routeSummary = summary,
            sender = sender, recipient = recipient,
            slippageTolerance = 100,
        ))
        if (buildResp.code != 0) throw RuntimeException(buildResp.message ?: "kyber build error")
        val buildData = buildResp.data ?: throw RuntimeException("kyber: missing build data")
        val isNativeIn = sellToken.lowercase() == DexTokenList.NATIVE_TOKEN.lowercase()
        return DexTx(
            to = buildData.routerAddress,
            data = buildData.data,
            value = if (isNativeIn) amount else "0",
            gas = buildData.gas,
            amountOutRaw = summary.amountOut,
            allowanceTarget = buildData.routerAddress,
        )
    }

    private fun toRawAmount(humanAmount: String, decimals: Int): String {
        val bd = humanAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return bd.movePointRight(decimals).toBigInteger().toString()
    }

    private fun rawToAmount(raw: String, decimals: Int): BigDecimal {
        val bi = raw.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return bi.movePointLeft(decimals)
    }

    private fun toChainId(chain: String): Int {
        return DexTokenList.chainIds[chain.lowercase()]
            ?: throw RuntimeException("Unknown chain: $chain")
    }

    private fun createDexProvider(id: String, name: String) = SwapProvider(
        providerId = id, name = name,
        type = ExchangeProviderType.DEX,
        rateTypes = listOf(RateType.FLOAT),
        imageLarge = "",
        termsOfUse = null,
        privacyPolicy = null,
        slippage = null,
    )

    private fun CryptoCurrency.toLeastTokenInfo(): LeastTokenInfo? {
        val contractAddress = when (this) {
            is CryptoCurrency.Token -> contractAddress
            is CryptoCurrency.Coin -> DexTokenList.NATIVE_TOKEN
        }
        val networkId = network.rawId
        // Map network rawId to DEX chain name
        val chainName = when {
            networkId.contains("arbitrum", ignoreCase = true) -> "arbitrum"
            networkId.contains("optimism", ignoreCase = true) -> "optimism"
            networkId.contains("base", ignoreCase = true) -> "base"
            networkId.contains("polygon", ignoreCase = true) || networkId.contains("matic", ignoreCase = true) -> "polygon"
            networkId.contains("bsc", ignoreCase = true) || networkId.contains("bnb", ignoreCase = true) -> "bsc"
            networkId.contains("ethereum", ignoreCase = true) || networkId == "1" -> "ethereum"
            else -> return null
        }
        return LeastTokenInfo(contractAddress = contractAddress, network = chainName)
    }
}
