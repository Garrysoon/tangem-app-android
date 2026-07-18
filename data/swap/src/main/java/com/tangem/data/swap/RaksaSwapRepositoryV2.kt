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
    private val dexProviders = listOf(dexProvider("paraswap", "Paraswap"), dexProvider("kyberswap", "KyberSwap"))
    private val thorchainProvider = ExpressProvider(
        providerId = "thorchain", name = "THORChain", type = ExpressProviderType.DEX,
        imageLarge = "", termsOfUse = null, privacyPolicy = null,
        slippage = null, rateTypes = listOf(ExpressRateType.Float),
    )
    private val bridgeProvider = ExpressProvider(
        providerId = "across", name = "Across Bridge", type = ExpressProviderType.DEX,
        imageLarge = "", termsOfUse = null, privacyPolicy = null,
        slippage = null, rateTypes = listOf(ExpressRateType.Float),
    )

    override suspend fun getPairs(
        primarySwapCurrencyStatus: SwapCurrencyStatus,
        secondarySwapCurrencyStatus: SwapCurrencyStatus,
        filterProviderTypes: List<ExpressProviderType>,
        swapTxType: SwapTxType,
    ): List<SwapPairModel> = withContext(coroutineDispatcher.default) {
        val fromChain = chainOf(primarySwapCurrencyStatus.currency)
        val toChain = chainOf(secondarySwapCurrencyStatus.currency)
        if (fromChain == toChain) {
            // Same-chain: DEX providers (Paraswap, KyberSwap)
            val p = dexProviders.filter { it.type in filterProviderTypes }
            listOf(SwapPairModel(
                from = primarySwapCurrencyStatus.status,
                to = secondarySwapCurrencyStatus.status,
                providers = p,
            ))
        } else if (needsBridge(fromChain, toChain)) {
            // Cross-chain EVM: Across bridge
            listOf(SwapPairModel(
                from = primarySwapCurrencyStatus.status,
                to = secondarySwapCurrencyStatus.status,
                providers = listOf(bridgeProvider),
            ))
        } else if (isUtxoBridge(fromChain, toChain)) {
            // Cross-chain BTC/LTC: THORChain
            listOf(SwapPairModel(
                from = primarySwapCurrencyStatus.status,
                to = secondarySwapCurrencyStatus.status,
                providers = listOf(thorchainProvider),
            ))
        } else {
            emptyList()
        }
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
    ): List<SwapPairModel> = withContext(coroutineDispatcher.default) {
        val p = dexProviders.filter { it.type in filterProviderTypes }
        val allDexAddresses = getAllDexAddresses()
        cryptoCurrencyList.mapNotNull { currency ->
            val addr = addr(currency)
            if (addr.lowercase() in allDexAddresses) {
                SwapPairModel(
                    from = CryptoCurrencyStatus(currency = currency, value = CryptoCurrencyStatus.Loading),
                    to = CryptoCurrencyStatus(currency = initialCurrency, value = CryptoCurrencyStatus.Loading),
                    providers = p,
                )
            } else null
        }
    }

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
            "bitcoin" in id -> "bitcoin"
            "litecoin" in id -> "litecoin"
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

    private fun getAllDexAddresses(): Set<String> {
        val addresses = mutableSetOf<String>()
        for (list in listOf(DEX_TOKENS_ETHEREUM, DEX_TOKENS_ARBITRUM, DEX_TOKENS_OPTIMISM, DEX_TOKENS_BASE, DEX_TOKENS_POLYGON, DEX_TOKENS_BSC)) {
            addresses.addAll(list.map { it.lowercase() })
        }
        addresses.add(NATIVE.lowercase())
        return addresses
    }

    private val DEX_TOKENS_ETHEREUM = listOf("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "0xdAC17F958D2ee523a2206206994597C13D831ec7", "0x6B175474E89094C44Da98b954EedeAC495271d0F", "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599")
    private val DEX_TOKENS_ARBITRUM = listOf("0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f", "0x912CE59144191C1204E64559FE8253a0e49E6548")
    private val DEX_TOKENS_OPTIMISM = listOf("0x4200000000000000000000000000000000000006", "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "0x68f180fcCe6836688e9084f035309E29Bf0A2095", "0x4200000000000000000000000000000000000042")
    private val DEX_TOKENS_BASE = listOf("0x4200000000000000000000000000000000000006", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "0xd9aAEc86B65D86f6A7B5B1b0c42FFA531710b6CA", "0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb", "0x0555E30da8f98308EdB960aa94C0Db47230d2B9c")
    private val DEX_TOKENS_POLYGON = listOf("0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063", "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", "0x1BFD67037B42CF73acF2047067bd4F2C47D9BfD6")
    private val DEX_TOKENS_BSC = listOf("0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "0x55d398326f99059fF775485246999027B3197955", "0x2170Ed0880ac9A755fd29B2688956BD959F933F8", "0x0555E30da8f98308EdB960aa94C0Db47230d2B9c")

    private val ACROSS_SUPPORTED_CHAINS = setOf("ethereum", "arbitrum", "polygon", "optimism", "base", "bsc")

    private fun isUtxoBridge(fromChain: String, toChain: String): Boolean {
        val utxo = setOf("bitcoin", "litecoin")
        val evm = ACROSS_SUPPORTED_CHAINS
        return (fromChain.lowercase() in utxo && toChain.lowercase() in evm) ||
               (fromChain.lowercase() in evm && toChain.lowercase() in utxo)
    }

    private fun needsBridge(fromChain: String, toChain: String): Boolean {
        return fromChain.lowercase() != toChain.lowercase() &&
            ACROSS_SUPPORTED_CHAINS.contains(fromChain.lowercase()) &&
            ACROSS_SUPPORTED_CHAINS.contains(toChain.lowercase())
    }

    private fun chainId(chain: String): Int = CHAIN_IDS[chain.lowercase()] ?: 1
}
