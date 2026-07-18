package com.tangem.feature.swap

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.tangem.domain.express.models.ExpressOperationType
import com.tangem.domain.models.currency.CryptoCurrency
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.datasource.api.swap.AcrossBridgeApi
import com.tangem.datasource.api.swap.KyberSwapApi
import com.tangem.datasource.api.swap.ParaswapApi
import com.tangem.datasource.api.swap.ThorchainApi
import com.tangem.datasource.api.swap.models.KyberBuildRequest
import com.tangem.datasource.api.swap.models.ParaswapTxRequest
import com.tangem.feature.swap.domain.api.SwapRepository
import com.tangem.feature.swap.domain.models.ExpressDataError
import com.tangem.feature.swap.domain.models.SwapAmount
import com.tangem.feature.swap.domain.models.domain.*
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger
import javax.inject.Inject

internal class RaksaSwapRepository @Inject constructor(
    private val paraswapApi: ParaswapApi,
    private val kyberSwapApi: KyberSwapApi,
    private val acrossBridgeApi: AcrossBridgeApi,
    private val thorchainApi: ThorchainApi,
    private val coroutineDispatcher: CoroutineDispatcherProvider,
) : SwapRepository {

    private data class DexResult(
        val source: String,
        val amountOutRaw: String,
        val gas: Long?,
        val allowanceContract: String?,
    )

    companion object {
        private const val NATIVE = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
        private val CHAIN_IDS = mapOf("ethereum" to 1, "arbitrum" to 42161, "optimism" to 10, "base" to 8453, "polygon" to 137, "bsc" to 56)
        private val KYBER_SLUGS = mapOf("ethereum" to "ethereum", "arbitrum" to "arbitrum", "optimism" to "optimism", "base" to "base", "polygon" to "polygon", "bsc" to "bsc")
    }

    private fun dexProvider(id: String, name: String) = SwapProvider(
        providerId = id, name = name, type = ExchangeProviderType.DEX,
        rateTypes = listOf(RateType.FLOAT), imageLarge = "",
        termsOfUse = null, privacyPolicy = null, slippage = null,
    )
    private val providers = listOf(dexProvider("paraswap", "Paraswap"), dexProvider("kyberswap", "KyberSwap"))

    override suspend fun getPairs(userWallet: UserWallet, initialCurrency: LeastTokenInfo, currencyList: List<CryptoCurrency>): PairsWithProviders = getPairsOnly(userWallet, initialCurrency, currencyList)

    override suspend fun getPairsOnly(userWallet: UserWallet, initialCurrency: LeastTokenInfo, currencyList: List<CryptoCurrency>, isIgnoreExpress: Boolean): PairsWithProviders = withContext(coroutineDispatcher.io) {
        PairsWithProviders(pairs = emptyList(), allProviders = providers)
    }

    override suspend fun findBestQuote(
        userWallet: UserWallet,
        fromContractAddress: String, fromNetwork: String,
        toContractAddress: String, toNetwork: String,
        fromAmount: String, fromDecimals: Int, toDecimals: Int,
        providerId: String, rateType: RateType,
    ): Either<ExpressDataError, QuoteModel> = withContext(coroutineDispatcher.io) {
        try {
            android.util.Log.d("RaksaSwap", "findBestQuote: from=$fromNetwork to=$toNetwork fromAddr=$fromContractAddress provider=$providerId")
            val safeFromAddr = if (fromContractAddress.isBlank() || fromContractAddress == "0" || fromContractAddress == "0x") NATIVE else fromContractAddress
            val safeToAddr = if (toContractAddress.isBlank() || toContractAddress == "0" || toContractAddress == "0x") NATIVE else toContractAddress

            if (fromNetwork.lowercase() != toNetwork.lowercase() || providerId.lowercase() == "thorchain") {
                val isUtxo = fromNetwork.lowercase().contains("bitcoin") || fromNetwork.lowercase().contains("litecoin") || toNetwork.lowercase().contains("bitcoin") || toNetwork.lowercase().contains("litecoin")
                if (isUtxo) return@withContext fetchThorchainQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
                return@withContext fetchAcrossQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
            }
            val isUtxoFrom = fromNetwork.lowercase().contains("bitcoin") || fromNetwork.lowercase().contains("litecoin")
            val isUtxoTo = toNetwork.lowercase().contains("bitcoin") || toNetwork.lowercase().contains("litecoin")
            if (isUtxoFrom || isUtxoTo) return@withContext fetchThorchainQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
            if (!DexTokenList.isChainSupported(fromNetwork)) return@withContext ExpressDataError.UnknownError().left()
            if (providerId.lowercase() == "across") return@withContext fetchAcrossQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)

            val results = mutableListOf<DexResult>()
            try {
                val resp = paraswapApi.getPrices(safeFromAddr, safeToAddr, fromAmount, fromDecimals, toDecimals, network = toChainId(fromNetwork))
                resp.priceRoute?.let { results.add(DexResult("paraswap", it.destAmount, null, it.tokenTransferProxy)) }
            } catch (_: Exception) {}
            try {
                val slug = KYBER_SLUGS[fromNetwork.lowercase()] ?: return@withContext ExpressDataError.UnknownError().left()
                val resp = kyberSwapApi.getRoutes(slug, safeFromAddr, safeToAddr, fromAmount)
                if (resp.code == 0) resp.data?.routeSummary?.let { results.add(DexResult("kyberswap", it.amountOut, it.gas?.toLongOrNull(), null)) }
            } catch (_: Exception) {}

            if (results.isEmpty()) return@withContext ExpressDataError.UnknownError().left()
            val best = results.maxByOrNull { it.amountOutRaw.toBigDecimalOrNull() ?: BigDecimal.ZERO } ?: results.first()
            val amountOut = rawToAmount(best.amountOutRaw, toDecimals)
            QuoteModel(toTokenAmount = SwapAmount(amountOut, toDecimals), allowanceContract = best.allowanceContract, txType = ExpressTxType.SWAP).right()
        } catch (e: Exception) { ExpressDataError.UnknownError().left() }
    }

    override suspend fun getExchangeData(
        userWallet: UserWallet,
        fromContractAddress: String, fromNetwork: String,
        toContractAddress: String, fromAddress: String,
        toNetwork: String, fromAmount: String,
        fromDecimals: Int, toDecimals: Int,
        providerId: String, rateType: RateType,
        toAddress: String, expressOperationType: ExpressOperationType,
        refundAddress: String?, refundExtraId: String?, toExtraId: String?,
    ): Either<ExpressDataError, SwapDataModel> = withContext(coroutineDispatcher.io) {
        // TODO: Implement swap execution (sign + broadcast)
        ExpressDataError.UnknownError().left()
    }

override suspend fun getExchangeStatus(userWallet: UserWallet?, userWalletId: UserWalletId, txId: String): Either<UnknownError, ExchangeStatusModel> = UnknownError().left()

    override suspend fun exchangeSent(userWallet: UserWallet, txId: String, fromNetwork: String, fromAddress: String, payInAddress: String, txHash: String, payInExtraId: String?): Either<ExpressDataError, Unit> = Unit.right()

    override suspend fun getStoredSwapUiMode(): SwapUIMode? = null
    override suspend fun storeSwapUiMode(mode: SwapUIMode) {}

    private fun thorchainAssetByAddress(chain: String, address: String): String? {
        val c = chain.lowercase().replace("-one", "")
        val addr = address.lowercase()
        val known = mapOf(
            "0xdac17f958d2ee523a2206206994597c13d831ec7" to "ETH.USDT-0xdAC17F958D2ee523a2206206994597C13D831ec7",
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to "ETH.USDC-0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
        )
        return known[addr]
    }

    private fun toChainId(chain: String): Int = CHAIN_IDS[chain.lowercase()] ?: 1
    private fun rawToAmount(raw: String, decimals: Int): BigDecimal = (raw.toBigDecimalOrNull() ?: BigDecimal.ZERO).movePointLeft(decimals)

    // ===== THORChain =====
    private fun thorchainAsset(chain: String, symbol: String): String? {
        val c = chain.lowercase().replace("-one", "")
        val s = symbol.uppercase()
        val map = mapOf(
            "bitcoin" to mapOf("BTC" to "BTC.BTC", "USDC" to "ETH.USDC-0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDT" to "ETH.USDT-0xdAC17F958D2ee523a2206206994597C13D831ec7"),
            "ethereum" to mapOf("ETH" to "ETH.ETH", "USDC" to "ETH.USDC-0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDT" to "ETH.USDT-0xdAC17F958D2ee523a2206206994597C13D831ec7", "WETH" to "ETH.ETH"),
            "litecoin" to mapOf("LTC" to "LTC.LTC"),
            "avalanche" to mapOf("AVAX" to "AVAX.AVAX"),
        )
        return map[c]?.get(s)
    }

    private suspend fun fetchThorchainQuote(fromAddr: String, fromChain: String, toAddr: String, toChain: String, amount: String, fromDec: Int, toDec: Int): Either<ExpressDataError, QuoteModel> {
        try {
            val fromSymbol = when { fromChain.lowercase().contains("bitcoin") -> "BTC"; fromChain.lowercase().contains("litecoin") -> "LTC"; else -> "ETH" }
            // Resolve THORChain asset from contract address
            val toAsset = thorchainAssetByAddress(toChain, toAddr) ?: thorchainAsset(toChain, when {
                toChain.lowercase().contains("bitcoin") -> "BTC"; toChain.lowercase().contains("ethereum") -> "ETH"; else -> "ETH"
            })
            if (toAsset == null) return ExpressDataError.UnknownError().left()
            val fromAsset = thorchainAsset(fromChain, fromSymbol) ?: return ExpressDataError.UnknownError().left()
            val rawAmount = amount
            val resp = thorchainApi.getQuote(rawAmount, fromAsset, toAsset)
            if (resp.error != null) return ExpressDataError.UnknownError().left()
            val expectedOut = resp.expectedAmountOut?.toLongOrNull() ?: 0L
            // THORChain returns amounts in 8 decimal precision
            val amountOutHuman = rawToAmount(expectedOut.toString(), 8)
            return QuoteModel(toTokenAmount = SwapAmount(amountOutHuman, 8), allowanceContract = null, txType = ExpressTxType.SWAP).right()
        } catch (e: Exception) { return ExpressDataError.UnknownError().left() }
    }

    // ===== Across =====
    private suspend fun fetchAcrossQuote(fromAddr: String, fromChain: String, toAddr: String, toChain: String, amount: String, fromDec: Int, toDec: Int): Either<ExpressDataError, QuoteModel> {
        try {
            val fromChainId = CHAIN_IDS[fromChain.lowercase()] ?: return ExpressDataError.UnknownError().left()
            val toChainId = CHAIN_IDS[toChain.lowercase()] ?: return ExpressDataError.UnknownError().left()
            val fees = acrossBridgeApi.getSuggestedFees(fromAddr, toAddr, fromChainId, toChainId, amount)
            val totalFee = (fees.relayFeeTotal?.toLongOrNull() ?: 0L) + (fees.lpFeeTotal?.toLongOrNull() ?: 0L)
            val amountIn = amount.toLongOrNull() ?: 0L
            val amountOut = (amountIn - totalFee).coerceAtLeast(0)
            val amountOutHuman = rawToAmount(amountOut.toString(), toDec)
            return QuoteModel(toTokenAmount = SwapAmount(amountOutHuman, toDec), allowanceContract = null, txType = ExpressTxType.SWAP).right()
        } catch (e: Exception) { return ExpressDataError.UnknownError().left() }
    }
}
