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
    private val thorchainApi: ThorchainApi,
    private val acrossBridgeApi: AcrossBridgeApi,
    private val odosApi: com.tangem.datasource.api.swap.OdosApi,
    private val coWSwapApi: com.tangem.datasource.api.swap.CoWSwapApi,
    private val veloraApi: com.tangem.datasource.api.swap.VeloraApi,
    private val liFiApi: com.tangem.datasource.api.swap.LiFiApi,
    private val providerRegistry: com.tangem.feature.swap.providers.DexProviderRegistry,
    private val coroutineDispatcher: CoroutineDispatcherProvider,
) : SwapRepository {

    private data class DexResult(
        val source: String,
        val amountOutRaw: String,
        val gas: Long?,
        val allowanceContract: String?,
    )

    companion object {
        private const val NATIVE = "0xEeeeeEeeeEeEeeEeEeEeeEEEeEeeeeEeeeeEEeE"
        private val CHAIN_IDS = mapOf("ethereum" to 1, "arbitrum" to 42161, "optimism" to 10, "base" to 8453, "polygon" to 137, "bsc" to 56)
        private val KYBER_SLUGS = mapOf("ethereum" to "ethereum", "arbitrum" to "arbitrum", "optimism" to "optimism", "base" to "base", "polygon" to "polygon", "bsc" to "bsc")
    }

    private fun dexProvider(id: String, name: String) = SwapProvider(
        providerId = id, name = name, type = ExchangeProviderType.DEX,
        rateTypes = listOf(RateType.FLOAT), imageLarge = "",
        termsOfUse = null, privacyPolicy = null, slippage = null,
    )
    private val providers = listOf(dexProvider("paraswap", "Paraswap"), dexProvider("kyberswap", "KyberSwap"), dexProvider("velora", "Velora"), dexProvider("odos", "Odos"), dexProvider("cowswap", "CoW Swap"), dexProvider("lifi", "LI.FI"))

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
        fromAddress: String?,
    ): Either<ExpressDataError, QuoteModel> = withContext(coroutineDispatcher.io) {
        try {
            android.util.Log.d("RaksaSwap", "findBestQuote: from=$fromNetwork to=$toNetwork fromAddr=$fromContractAddress provider=$providerId")
            val safeFromAddr = if (fromContractAddress.isBlank() || fromContractAddress == "0" || fromContractAddress == "0x") NATIVE else fromContractAddress
            val safeToAddr = if (toContractAddress.isBlank() || toContractAddress == "0" || toContractAddress == "0x") NATIVE else toContractAddress

            // LI.FI meta-aggregator (same-chain + cross-chain)
            if (providerId.lowercase() == "lifi") {
                if (!fromAddress.isNullOrBlank()) {
                    try {
                        val liFiFromChain = normalizeChainId(fromNetwork)
                        val liFiToChain = normalizeChainId(toNetwork)
                        val isFromUtxo = liFiFromChain.lowercase().contains("bitcoin") || liFiFromChain.lowercase().contains("litecoin")
                        val isToUtxo = liFiToChain.lowercase().contains("bitcoin") || liFiToChain.lowercase().contains("litecoin")
                        val fromTokenAddr = if (safeFromAddr == NATIVE) { if (isFromUtxo) "0x0000000000000000000000000000000000000000" else "0xEeeeeEeeeEeEeeEeEeEeeEEEeEeeeeEeeeeEEeE" } else safeFromAddr
                        val toTokenAddr = if (safeToAddr == NATIVE) { if (isToUtxo) "0x0000000000000000000000000000000000000000" else "0xEeeeeEeeeEeEeeEeEeEeeEEEeEeeeeEeeeeEEeE" } else safeToAddr
                        val resp = liFiApi.getQuote(fromChain = liFiFromChain, toChain = liFiToChain, fromToken = fromTokenAddr, toToken = toTokenAddr, fromAmount = fromAmount, fromAddress = fromAddress)
                        val toAmountStr = resp.estimate?.toAmount
                        if (toAmountStr != null) {
                            return@withContext QuoteModel(toTokenAmount = SwapAmount(rawToAmount(toAmountStr, toDecimals), toDecimals), allowanceContract = resp.estimate?.approvalAddress, txType = ExpressTxType.SWAP, providerId = "lifi").right()
                        }
                    } catch (e: Exception) { android.util.Log.e("RaksaSwap", "Li.FI error: " + e.message) }
                }
                // LI.FI failed - for cross-chain fall through to thorchain, for same-chain return error
                if (fromNetwork.lowercase() != toNetwork.lowercase()) {
                    android.util.Log.d("RaksaSwap", "Li.FI failed for cross-chain, falling back to thorchain")
                    return@withContext fetchThorchainQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
                }
                return@withContext ExpressDataError.UnknownError().left()
            }

            // Cross-chain routing: only thorchain, across, lifi support cross-chain
            if (fromNetwork.lowercase() != toNetwork.lowercase()) {
                return@withContext when (providerId.lowercase()) {
                    "thorchain" -> {
                        val isUtxo = fromNetwork.lowercase().contains("bitcoin") || fromNetwork.lowercase().contains("litecoin") || toNetwork.lowercase().contains("bitcoin") || toNetwork.lowercase().contains("litecoin")
                        if (isUtxo) return@withContext fetchThorchainQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
                        val isSameToken = safeFromAddr.lowercase() == safeToAddr.lowercase()
                        if (isSameToken) {
                            fetchAcrossQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
                        } else {
                            fetchSwapAndBridgeQuote(fromAddr = safeFromAddr, fromNetwork = fromNetwork, toAddr = safeToAddr, toNetwork = toNetwork, amount = fromAmount, fromDec = fromDecimals, toDec = toDecimals)
                        }
                    }
                    "across" -> fetchAcrossQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
                    "lifi" -> ExpressDataError.UnknownError().left() // handled above
                    else -> {
                        android.util.Log.d("RaksaSwap", "Provider=$providerId does not support cross-chain, falling back to thorchain")
                        fetchThorchainQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
                    }
                }
            }

            // UTXO same-chain (e.g. BTC->BTC): only thorchain supports this
            val isUtxoFrom = fromNetwork.lowercase().contains("bitcoin") || fromNetwork.lowercase().contains("litecoin")
            val isUtxoTo = toNetwork.lowercase().contains("bitcoin") || toNetwork.lowercase().contains("litecoin")
            if (isUtxoFrom || isUtxoTo) {
                if (providerId.lowercase() != "thorchain") {
                    android.util.Log.d("RaksaSwap", "Provider=$providerId does not support UTXO, falling back to thorchain")
                }
                return@withContext fetchThorchainQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)
            }
            if (!DexTokenList.isChainSupported(fromNetwork)) return@withContext ExpressDataError.UnknownError().left()
            if (providerId.lowercase() == "across") return@withContext fetchAcrossQuote(safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount, fromDecimals, toDecimals)

            // Delegate to provider registry - each provider queries only its own API
            val provider = providerRegistry.getProvider(providerId)
            if (provider != null && provider.isSupported(fromNetwork, toNetwork)) {
                android.util.Log.d("RaksaSwap", "Delegating to DexProvider: $providerId")
                return@withContext provider.getQuote(
                    userWallet = userWallet,
                    fromContractAddress = fromContractAddress,
                    fromNetwork = fromNetwork,
                    toContractAddress = toContractAddress,
                    toNetwork = toNetwork,
                    fromAmount = fromAmount,
                    fromDecimals = fromDecimals,
                    toDecimals = toDecimals,
                    fromAddress = fromAddress,
                )
            }
            android.util.Log.d("RaksaSwap", "Provider=$providerId not supported for $fromNetwork->$toNetwork")
            ExpressDataError.UnknownError().left()
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
        try {
            val safeFromAddr = if (fromContractAddress.isBlank() || fromContractAddress == "0" || fromContractAddress == "0x") NATIVE else fromContractAddress
            val safeToAddr = if (toContractAddress.isBlank() || toContractAddress == "0" || toContractAddress == "0x") NATIVE else toContractAddress

            android.util.Log.d("RaksaSwap", "getExchangeData: provider=$providerId from=$safeFromAddr to=$safeToAddr amount=$fromAmount network=$fromNetwork")

            when {
                providerId.lowercase() == "odos" -> buildOdosTx(
                    safeFromAddr, safeToAddr, fromAmount, fromDecimals, toDecimals,
                    fromNetwork, fromAddress, toAddress,
                )
                providerId.lowercase() == "paraswap" -> buildParaswapTx(
                    safeFromAddr, safeToAddr, fromAmount, fromDecimals, toDecimals,
                    fromNetwork, fromAddress, toAddress,
                )
                providerId.lowercase() == "kyberswap" -> buildKyberSwapTx(
                    safeFromAddr, safeToAddr, fromAmount, fromDecimals, toDecimals,
                    fromNetwork, fromAddress, toAddress,
                )
                providerId.lowercase() == "thorchain" -> buildThorchainTx(
                    safeFromAddr, fromNetwork, safeToAddr, toNetwork, fromAmount,
                    fromDecimals, toDecimals, fromAddress, toAddress,
                )
                providerId.lowercase() == "velora" -> {
                    android.util.Log.d("RaksaSwap", "Velora tx building (same as Paraswap)")
                    buildParaswapTx(
                        safeFromAddr, safeToAddr, fromAmount, fromDecimals, toDecimals,
                        fromNetwork, fromAddress, toAddress,
                    )
                }
                providerId.lowercase() == "cowswap" -> {
                    android.util.Log.w("RaksaSwap", "CoW Swap tx building not implemented")
                    ExpressDataError.UnknownError().left()
                }
                providerId.lowercase() == "across" -> buildAcrossTx(
                    safeFromAddr, safeToAddr, fromAmount, fromDecimals, toDecimals,
                    fromNetwork, toNetwork, fromAddress, toAddress,
                )
                else -> ExpressDataError.UnknownError().left()
            }
        } catch (e: Exception) {
            android.util.Log.e("RaksaSwap", "getExchangeData error: ${e.message}")
            ExpressDataError.UnknownError().left()
        }
    }

    private suspend fun buildParaswapTx(
        fromAddr: String, toAddr: String, amount: String,
        fromDec: Int, toDec: Int, network: String,
        userAddress: String, toAddress: String,
    ): Either<ExpressDataError, SwapDataModel> {
        // 1. Get fresh price route (Paraswap requires unmodified route)
        val chainId = toChainId(network)
        val priceResp = paraswapApi.getPrices(fromAddr, toAddr, amount, fromDec, toDec, network = chainId)
        val priceRoute = priceResp.priceRoute
            ?: return ExpressDataError.UnknownError().left()

        // 2. Apply slippage: destAmount * (1 - slippagePercent/100)
        val destAmount = priceRoute.destAmount.toLongOrNull() ?: 0L
        val slippageBps = 100 // default 1% = 100 basis points
        val minOut = (destAmount * (10000 - slippageBps) / 10000).toString()

        // 3. Build transaction via Paraswap /transactions endpoint
        val txRequest = com.tangem.datasource.api.swap.models.ParaswapTxRequest(
            srcToken = fromAddr,
            destToken = toAddr,
            srcAmount = amount,
            destAmount = minOut,
            priceRoute = priceRoute,
            userAddress = userAddress,
            receiver = toAddress.ifBlank { userAddress },
            srcDecimals = fromDec,
            destDecimals = toDec,
        )
        val txResp = paraswapApi.buildTransaction(chainId, "true", txRequest)

        val amountOut = rawToAmount(priceRoute.destAmount, toDec)
        android.util.Log.d("RaksaSwap", "Paraswap tx built: to=${txResp.to} dataLen=${txResp.data?.length ?: 0} gas=${txResp.gas}")

        return SwapDataModel(
            toTokenAmount = SwapAmount(amountOut, toDec),
            transaction = com.tangem.feature.swap.domain.models.domain.ExpressTransactionModel.DEX(
                fromAmount = SwapAmount(amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO, fromDec),
                toAmount = SwapAmount(amountOut, toDec),
                txValue = txResp.value ?: "0",
                txId = "",
                txTo = txResp.to ?: "",
                txExtraId = null,
                txFrom = userAddress,
                txData = txResp.data ?: "",
                otherNativeFeeWei = null,
                gas = txResp.gas?.toBigIntegerOrNull(),
                allowanceContract = priceRoute.tokenTransferProxy,
            ),
        ).right()
    }

        private suspend fun buildKyberSwapTx(
        fromAddr: String, toAddr: String, amount: String,
        fromDec: Int, toDec: Int, fromChain: String,
        userAddress: String, toAddress: String,
    ): Either<ExpressDataError, SwapDataModel> {
        // KyberSwap tx building not yet implemented - return error
        android.util.Log.w("RaksaSwap", "KyberSwap tx building not implemented")
        return ExpressDataError.UnknownError().left()
    }

    /**
     * Build THORChain swap transaction.
     * THORChain works by sending native coins to a vault/router with a memo.
     * Memo format: =:DEST_ASSET:DEST_ADDRESS:
     *
     * For cross-chain: send to THORChain vault on source chain
     * For same-chain: send to THORChain router on same chain
     */
    private suspend fun buildThorchainTx(
        fromAddr: String, fromChain: String, toAddr: String, toChain: String,
        amount: String, fromDec: Int, toDec: Int,
        userAddress: String, toAddress: String,
    ): Either<ExpressDataError, SwapDataModel> {
        try {
            // Resolve THORChain assets
            val fromAsset = thorchainAssetByAddress(fromChain, fromAddr) ?: thorchainAsset(fromChain, when {
                fromChain.lowercase().contains("bitcoin") -> "BTC"
                fromChain.lowercase().contains("litecoin") -> "LTC"
                else -> "ETH"
            })
            val toAsset = thorchainAssetByAddress(toChain, toAddr) ?: thorchainAsset(toChain, when {
                toChain.lowercase().contains("bitcoin") -> "BTC"
                toChain.lowercase().contains("ethereum") -> "ETH"
                else -> "ETH"
            })

            if (fromAsset == null || toAsset == null) {
                return ExpressDataError.UnknownError().left()
            }

            // Build memo: =:DEST_ASSET:DEST_ADDRESS:
            val memo = "=:${toAsset}:${toAddress}:"
            android.util.Log.d("RaksaSwap", "THORChain memo: $memo (fromAsset=$fromAsset, toAsset=$toAsset)")

            // Get THORChain inbound addresses to find the vault/router
            val vaultAddress = getThorchainVaultAddress(fromChain)

            // Get expected output from quote
            val thorAmount = amount.toBigDecimal().movePointLeft(fromDec).movePointRight(8).toLong().toString()
            val resp = thorchainApi.getQuote(thorAmount, fromAsset, toAsset)
            val expectedOut = resp.expectedAmountOut?.toLongOrNull() ?: 0L
            val amountOut = rawToAmount(expectedOut.toString(), fromDec)

            android.util.Log.d("RaksaSwap", "THORChain tx: vault=$vaultAddress memo=$memo amountOut=$amountOut")

            // For Bitcoin: txValue = amount (satoshis), txTo = vault address, txData = memo
            // For Ethereum: txValue = amount (wei), txTo = router contract, txData = memo (in calldata)
            val isBitcoin = fromChain.lowercase().contains("bitcoin") || fromChain.lowercase().contains("litecoin")
            val txTo = vaultAddress
            val txData = if (isBitcoin) "" else depositWithExpiryCalldata(vaultAddress, fromAddr, amount, memo)

            return SwapDataModel(
                toTokenAmount = SwapAmount(amountOut, toDec),
                transaction = com.tangem.feature.swap.domain.models.domain.ExpressTransactionModel.DEX(
                    fromAmount = SwapAmount(amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO, fromDec),
                    toAmount = SwapAmount(amountOut, toDec),
                    txValue = if (isBitcoin) amount else amount, // For ETH: send native ETH + memo
                    txId = "",
                    txTo = txTo,
                    txExtraId = null,
                    txFrom = userAddress,
                    txData = txData, // Memo encoded as calldata for EVM, empty for BTC
                    otherNativeFeeWei = null,
                    gas = null,
                    allowanceContract = null,
                ),
            ).right()
        } catch (e: Exception) {
            android.util.Log.e("RaksaSwap", "THORChain tx build error: ${e.message}")
            return ExpressDataError.UnknownError().left()
        }
    }

    /**
     * Get THORChain vault address for the source chain.
     * For BTC: THORChain vault (from inbound addresses API)
     * For ETH: THORChain Router contract
     */
    // THORChain vault/router addresses (fetched from API 2025-07-19)
    // WARNING: These change regularly! Always prefer fetching from API.
    private val THORCHAIN_VAULTS = mapOf(
        "BTC" to "bc1q2nfxrvvg67nhey0gk0cc8ke2ea4akge8kskyyq",
        "LTC" to "ltc1q2nfxrvvg67nhey0gk0cc8ke2ea4akge8jvvqus",
        "ETH" to "0xD37BbE5744D730a1d98d8DC97c42F0Ca46aD7146",
        "AVAX" to "0x00dc6100103BC402d490aEE3F9a5560cBd91f1d4",
        "MATIC" to "0xD37BbE5744D730a1d98d8DC97c42F0Ca46aD7146",
    )

    private val THORCHAIN_CHAIN_MAP = mapOf(
        "BITCOIN" to "BTC", "LITECOIN" to "LTC", "ETHEREUM" to "ETH",
        "ARBITRUM" to "ETH", "POLYGON" to "MATIC", "OPTIMISM" to "ETH",
        "BASE" to "ETH", "AVALANCHE" to "AVAX",
    )

    /**
     * Get THORChain vault/router address.
     * Prefers live API, falls back to cached addresses.
     * Doc says: "Never cache vault addresses, they churn regularly!"
     */
    private suspend fun getThorchainVaultAddress(chain: String): String {
        val normalizedChain = chain.lowercase().replace("-one", "").replace("-pos", "").uppercase()
        val tcChain = THORCHAIN_CHAIN_MAP[normalizedChain] ?: "ETH"

        // Try live API first
        try {
            val conn = java.net.URL("https://gateway.liquify.com/chain/thorchain_api/thorchain/inbound_addresses")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val addrs = org.json.JSONArray(body)
                for (i in 0 until addrs.length()) {
                    val entry = addrs.getJSONObject(i)
                    if (entry.optString("chain") == tcChain && !entry.optBoolean("halted")) {
                        // For EVM chains, use router if available; for UTXO, use vault
                        val router = entry.optString("router", "")
                        val vault = entry.optString("address", "")
                        val result = if (router.isNotBlank()) router else vault
                        android.util.Log.d("RaksaSwap", "THORChain vault (live): $tcChain -> $result")
                        if (result.isNotBlank()) return result
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RaksaSwap", "Failed to fetch THORChain vault from API: ${e.message}")
        }

        // Fallback to cached addresses
        val fallback = THORCHAIN_VAULTS[tcChain] ?: "0xD37BbE5744D730a1d98d8DC97c42F0Ca46aD7146"
        android.util.Log.d("RaksaSwap", "THORChain vault (fallback): $tcChain -> $fallback")
        return fallback
    }

    /**
     * Encode THORChain memo as EVM calldata for the Router contract.
     * The Router contract accepts: function deposit(address asset, uint256 amount, bytes memo)
     * Selector for deposit(address,uint256,bytes) = 0xb0431182
     */
    private fun depositWithExpiryCalldata(vault: String, asset: String, amount: String, memo: String): String {
        val selector = "e517ced0"
        val paddedVault = "000000000000000000000000" + vault.removePrefix("0x").lowercase()
        val paddedAsset = "000000000000000000000000" + asset.removePrefix("0x").lowercase()
        val paddedAmount = java.math.BigInteger(amount).toString(16).padStart(64, '0')
        val memoOffset = "00000000000000000000000000000000000000000000000000000000000000a0"
        val memoBytes = memo.toByteArray(Charsets.UTF_8)
        val memoLen = memoBytes.size.toString(16).padStart(64, '0')
        val memoHex = memoBytes.joinToString("") { "%02x".format(it) }
        val paddedMemoHex = memoHex.padStart(((memoBytes.size + 31) / 32) * 64, '0')
        val expiryTs = ((System.currentTimeMillis() / 1000) + 7200).toString(16).padStart(64, '0')
        return "0x" + selector + paddedVault + paddedAsset + paddedAmount + memoOffset + memoLen + paddedMemoHex + expiryTs
    }

    private suspend fun buildAcrossTx(
        fromAddr: String, toAddr: String, amount: String,
        fromDec: Int, toDec: Int, fromChain: String, toChain: String,
        userAddress: String, toAddress: String,
    ): Either<ExpressDataError, SwapDataModel> {
        // Build Across SpokePool calldata for depositV3
        val fromChainId = CHAIN_IDS[normalizeChainId(fromChain)] ?: return ExpressDataError.UnknownError().left()
        val toChainId = CHAIN_IDS[normalizeChainId(toChain)] ?: return ExpressDataError.UnknownError().left()
        val feeData = acrossBridgeApi.getSuggestedFees(token = fromAddr, outputToken = toAddr, originChainId = fromChainId, destinationChainId = toChainId, amount = amount, depositor = null, recipient = toAddress)
        val outputAmount = feeData.outputAmount?.toLongOrNull() ?: 0L

        // Encode Across depositV3 calldata
        // depositV3(address recipient, address destinationToken, address originToken, uint256 amounts, ...)
        val selector = "0xdeace8f5" // depositV3 selector
        val calldata = buildAcrossCalldata(
            recipient = toAddress,
            destinationToken = toAddr,
            originToken = fromAddr,
            amounts = amount,
            originChainId = fromChainId,
            destinationChainId = toChainId,
            relayerFee = feeData.relayFeeTotal?.toLongOrNull() ?: 0L,
        )

        val amountOut = rawToAmount(outputAmount.toString(), toDec)
        val spokePool = DexTokenList.ACROSS_SPOKE_POOLS[toChainId] ?: ""

        android.util.Log.d("RaksaSwap", "Across tx built: to=$spokePool dataLen=${calldata.length} outputAmount=$outputAmount")

        return SwapDataModel(
            toTokenAmount = SwapAmount(amountOut, toDec),
            transaction = com.tangem.feature.swap.domain.models.domain.ExpressTransactionModel.DEX(
                fromAmount = SwapAmount(amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO, fromDec),
                toAmount = SwapAmount(amountOut, toDec),
                txValue = "0",
                txId = "",
                txTo = DexTokenList.ACROSS_SPOKE_POOLS[toChainId] ?: "",
                txExtraId = null,
                txFrom = userAddress,
                txData = calldata,
                otherNativeFeeWei = null,
                gas = null,
                allowanceContract = fromAddr,
            ),
        ).right()
    }

    private fun buildAcrossCalldata(
        recipient: String, destinationToken: String, originToken: String,
        amounts: String, originChainId: Int, destinationChainId: Int,
        relayerFee: Long,
    ): String {
        // depositV3(address recipient, address destinationToken, address originToken,
        //          uint256 amounts, uint256 relayerFee, uint256 originChainId, uint256 destinationChainId)
        val selector = "0xdeace8f5"
        val paddedRecipient = "000000000000000000000000${recipient.removePrefix("0x").lowercase()}"
        val paddedDestToken = "000000000000000000000000${destinationToken.removePrefix("0x").lowercase()}"
        val paddedOriginToken = "000000000000000000000000${originToken.removePrefix("0x").lowercase()}"
        val paddedAmounts = java.math.BigInteger(amounts).toString(16).padStart(64, '0')
        val paddedRelayerFee = java.math.BigInteger.valueOf(relayerFee).toString(16).padStart(64, '0')
        val paddedOriginChainId = java.math.BigInteger.valueOf(originChainId.toLong()).toString(16).padStart(64, '0')
        val paddedDestChainId = java.math.BigInteger.valueOf(destinationChainId.toLong()).toString(16).padStart(64, '0')
        return "0x$selector$paddedRecipient$paddedDestToken$paddedOriginToken$paddedAmounts$paddedRelayerFee$paddedOriginChainId$paddedDestChainId"
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

    private fun toChainId(chain: String): Int = CHAIN_IDS[normalizeChainId(chain)] ?: 1
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
            // Resolve THORChain asset from contract address for BOTH from and to
            val fromAsset = thorchainAssetByAddress(fromChain, fromAddr) ?: thorchainAsset(fromChain, when {
                fromChain.lowercase().contains("bitcoin") -> "BTC"; fromChain.lowercase().contains("litecoin") -> "LTC"; else -> "ETH"
            })
            val toAsset = thorchainAssetByAddress(toChain, toAddr) ?: thorchainAsset(toChain, when {
                toChain.lowercase().contains("bitcoin") -> "BTC"; toChain.lowercase().contains("ethereum") -> "ETH"; else -> "ETH"
            })
            if (fromAsset == null || toAsset == null) return ExpressDataError.UnknownError().left()
            // THORChain gateway expects amounts in 8-decimal precision for ALL assets
            // Convert: native_decimals -> float -> 8 decimals
            // ETH: 10^18 -> 1.0 -> 10^8. USDT: 10^10 -> 10000.0 -> 10^12. BTC: 10^8 -> 1.0 -> 10^8
            val thorAmount = amount.toBigDecimal().movePointLeft(fromDec).movePointRight(8).toLong().toString()
            android.util.Log.d("RaksaSwap", "THORChain: fromAsset=$fromAsset toAsset=$toAsset amount=$thorAmount (orig=$amount fromDec=$fromDec)")
            val resp = thorchainApi.getQuote(thorAmount, fromAsset, toAsset)
            if (resp.error != null) return ExpressDataError.UnknownError().left()
            val expectedOut = resp.expectedAmountOut?.toLongOrNull() ?: 0L
            // THORChain gateway returns amounts in 8-decimal precision for ALL assets
            val amountOutHuman = rawToAmount(expectedOut.toString(), 8)
            android.util.Log.d("RaksaSwap", "THORChain result: expectedOut=$expectedOut amountOutHuman=$amountOutHuman")
            return QuoteModel(toTokenAmount = SwapAmount(amountOutHuman, toDec), allowanceContract = null, txType = ExpressTxType.SWAP).right()
        } catch (e: Exception) { return ExpressDataError.UnknownError().left() }
    }

    private fun getTokenDecimals(address: String, chain: String): Int {
        val addr = address.lowercase()
        // Known token decimals: ETH=18, USDC=6, USDT=6, WETH=18
        return when {
            addr == NATIVE.lowercase() || addr == "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2".lowercase() -> 18
            addr.startsWith("0x") && addr != NATIVE.lowercase() -> {
                // ERC-20 tokens: check known addresses
                when (addr) {
                    "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" -> 6 // USDC ETH
                    "0xaf88d065e77c8cc2239327c5edb3a432268e5831" -> 6 // USDC Arb
                    "0x2791bca1f2de4661ed88a30c99a7a9449aa84174" -> 6 // USDC.e Polygon
                    "0x0b2c639c533813f4aa9d7837caf62653d097ff85" -> 6 // USDC OP
                    "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913" -> 6 // USDC Base
                    "0xdac17f958d2ee523a2206206994597c13d831ec7" -> 6 // USDT ETH
                    "0xfd086b7cd5c481dcc9c85ebe478a1c0b69fcbb9" -> 6 // USDT Arb
                    "0xc2132d05d31c914a87c6611c10748aeb04b58e8f" -> 6 // USDT Polygon
                    "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58" -> 6 // USDT OP
                    "0x68f180fcce6836688e9084f035309e29bf0a2095" -> 8 // WBTC
                    "0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f" -> 8 // WBTC Arb
                    else -> 18 // Default to ETH decimals
                }
            }
            else -> 18
        }
    }

    // ===== Cross-token cross-chain: swap + bridge =====
    /**
     * Estimate total gas cost for swap+bridge in USD (approximate).
     * Returns Triple(approveUsd, swapUsd, bridgeUsd)
     */
    private fun estimateGasCosts(fromChain: String, toChain: String): Triple<String, String, String> {
        // Rough estimates based on chain
        val approveGas = when {
            fromChain.lowercase().contains("ethereum") -> "\$2-5"
            else -> "\$0.01-0.1"
        }
        val swapGas = when {
            fromChain.lowercase().contains("ethereum") -> "\$5-15"
            fromChain.lowercase().contains("arbitrum") -> "\$0.1-0.5"
            else -> "\$0.5-2"
        }
        val bridgeGas = when {
            toChain.lowercase().contains("arbitrum") -> "\$1-3"
            toChain.lowercase().contains("polygon") -> "\$0.01-0.05"
            else -> "\$1-5"
        }
        return Triple(approveGas, swapGas, bridgeGas)
    }

    private suspend fun fetchSwapAndBridgeQuote(
        fromAddr: String, fromNetwork: String,
        toAddr: String, toNetwork: String,
        amount: String, fromDec: Int, toDec: Int,
    ): Either<ExpressDataError, QuoteModel> {
        try {
            // Step 1: Find USDC address on source chain (same token symbol, different chain)
            val nativeFrom = fromNetwork.lowercase().replace("-one", "").replace("-pos", "")
            val sameTokenOnSource = findSameTokenAddress(toAddr, nativeFrom)
            if (sameTokenOnSource.isNullOrBlank()) {
                return ExpressDataError.UnknownError().left()
            }
            val safeSameToken = if (sameTokenOnSource.isBlank() || sameTokenOnSource == "0") NATIVE else sameTokenOnSource

            // Step 2: Swap on source chain (e.g. USDT -> USDC on Ethereum via Paraswap)
            android.util.Log.d("RaksaSwap", "SwapAndBridge step1: swap $fromAddr->${safeSameToken} on $fromNetwork")
            val sameTokenDecimals = getTokenDecimals(safeSameToken, nativeFrom)
            val swapResult = fetchParaswapQuote(fromAddr, safeSameToken, amount, fromDec, sameTokenDecimals, fromNetwork)
            val swapAmount = swapResult.fold({ return ExpressDataError.UnknownError().left() }, { it })

            // Convert swap output to raw units for bridge input
            val bridgeAmount = swapAmount.movePointRight(toDec).toLong().toString()
            android.util.Log.d("RaksaSwap", "SwapAndBridge step2: bridge $safeSameToken($bridgeAmount) $fromNetwork->$toNetwork")

            // Step 3: Bridge same token across chains (e.g. USDC ETH -> USDC Arb via Across)
            val nativeTo = toNetwork.lowercase().removeSuffix("-one").removeSuffix("-pos")
            val destTokenAddr = findSameTokenAddress(safeSameToken, nativeTo) ?: safeSameToken
            val safeDestToken = if (destTokenAddr.isBlank() || destTokenAddr == "0") NATIVE else destTokenAddr

            // Pre-flight: check bridge CAN fill before returning quote
            val fromChainId = CHAIN_IDS[normalizeChainId(fromNetwork)]
            val toChainId = CHAIN_IDS[normalizeChainId(toNetwork)]
            if (fromChainId != null && toChainId != null) {
                val preCheck = acrossBridgeApi.getSuggestedFees(
                    token = safeSameToken, outputToken = safeDestToken,
                    originChainId = fromChainId, destinationChainId = toChainId,
                    amount = bridgeAmount, depositor = null, recipient = null
                )
                if (preCheck.isBridgePaused == true) {
                    android.util.Log.e("RaksaSwap", "SwapAndBridge ABORTED: bridge is PAUSED")
                    return ExpressDataError.UnknownError().left()
                }
                val minDeposit = preCheck.limits?.minDeposit?.toLongOrNull() ?: 0L
                val maxInstant = preCheck.limits?.maxDepositInstant?.toLongOrNull() ?: Long.MAX_VALUE
                val bridgeAmountLong = bridgeAmount.toLongOrNull() ?: 0L
                if (bridgeAmountLong < minDeposit) {
                    android.util.Log.e("RaksaSwap", "SwapAndBridge ABORTED: bridgeAmount $bridgeAmount < minDeposit $minDeposit")
                    return ExpressDataError.UnknownError().left()
                }
                if (bridgeAmountLong > maxInstant) {
                    android.util.Log.w("RaksaSwap", "SwapAndBridge WARNING: bridgeAmount $bridgeAmount > maxInstant $maxInstant, will use slow path")
                }
                android.util.Log.d("RaksaSwap", "SwapAndBridge pre-flight OK: amount=$bridgeAmount min=$minDeposit maxInstant=$maxInstant fillTime=${preCheck.estimatedFillTimeSec}s")
            }

            val bridgeResult = fetchAcrossQuote(safeSameToken, fromNetwork, safeDestToken, toNetwork, bridgeAmount, toDec, toDec)
            return bridgeResult
        } catch (e: Exception) {
            android.util.Log.e("RaksaSwap", "SwapAndBridge error: ${e.message}")
            return ExpressDataError.UnknownError().left()
        }
    }

    private fun findSameTokenAddress(contractAddr: String, targetChain: String): String? {
        val addr = contractAddr.lowercase()
        // Build a complete cross-chain address map: token_id -> (chain -> address)
        val crossChainMap = mapOf(
            "usdc" to mapOf(
                "ethereum" to "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                "arbitrum" to "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "polygon" to "0x2791bca1f2de4661ed88a30c99a7a9449aa84174",
                "optimism" to "0x0b2c639c533813f4aa9d7837caf62653d097ff85",
                "base" to "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
                "bsc" to "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d",
            ),
            "usdt" to mapOf(
                "ethereum" to "0xdac17f958d2ee523a2206206994597c13d831ec7",
                "arbitrum" to "0xfd086b7cd5c481dcc9c85ebe478a1c0b69fcb99",
                "polygon" to "0xc2132d05d31c914a87c6611c10748aeb04b58e8f",
                "optimism" to "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58",
                "base" to "0xfde4c96c8593536e31f229ea8f37b2ada2699bb2",
                "bsc" to "0x55d398326f99059ff775485246999027b3197955",
            ),
        )

        for ((_, chainMap) in crossChainMap) {
            if (addr in chainMap.values.map { it.lowercase() }) {
                return chainMap[targetChain.lowercase()]
            }
        }
        return null
    }

    private suspend fun fetchParaswapQuote(
        fromAddr: String, toAddr: String,
        amount: String, fromDec: Int, toDec: Int,
        network: String,
    ): Either<ExpressDataError, BigDecimal> {
        return try {
            val resp = paraswapApi.getPrices(fromAddr, toAddr, amount, fromDec, toDec, network = toChainId(network))
            val route = resp.priceRoute ?: return ExpressDataError.UnknownError().left()
            val destAmount = route.destAmount.toBigDecimalOrNull() ?: return ExpressDataError.UnknownError().left()
            destAmount.movePointLeft(toDec).right()
        } catch (e: Exception) {
            ExpressDataError.UnknownError().left()
        }
    }

    // ===== Across =====
    private fun normalizeChainId(chain: String): String {
        val normalized = chain.lowercase().removeSuffix("-one").removeSuffix("-pos")
        return normalized
    }

    private suspend fun fetchAcrossQuote(fromAddr: String, fromChain: String, toAddr: String, toChain: String, amount: String, fromDec: Int, toDec: Int): Either<ExpressDataError, QuoteModel> {
        try {
            val fromChainId = CHAIN_IDS[normalizeChainId(fromChain)] ?: return ExpressDataError.UnknownError().left()
            val toChainId = CHAIN_IDS[normalizeChainId(toChain)] ?: return ExpressDataError.UnknownError().left()
            val fees = acrossBridgeApi.getSuggestedFees(token = fromAddr, outputToken = toAddr, originChainId = fromChainId, destinationChainId = toChainId, amount = amount, depositor = null, recipient = null)

            // Pre-flight checks
            val amountLong = amount.toLongOrNull() ?: 0L
            val limits = fees.limits
            if (fees.isBridgePaused == true) {
                android.util.Log.e("RaksaSwap", "Across: bridge is PAUSED")
                return ExpressDataError.UnknownError().left()
            }
            if (fees.isAmountTooLow == true) {
                android.util.Log.e("RaksaSwap", "Across: amount too low ($amount)")
                return ExpressDataError.UnknownError().left()
            }
            if (limits != null) {
                val minDeposit = limits.minDeposit?.toLongOrNull() ?: 0L
                val maxInstant = limits.maxDepositInstant?.toLongOrNull() ?: Long.MAX_VALUE
                if (amountLong < minDeposit) {
                    android.util.Log.e("RaksaSwap", "Across: amount $amount < minDeposit $minDeposit")
                    return ExpressDataError.UnknownError().left()
                }
                if (amountLong > maxInstant) {
                    android.util.Log.e("RaksaSwap", "Across: amount $amount > maxDepositInstant $maxInstant")
                    return ExpressDataError.UnknownError().left()
                }
            }

            // Use outputAmount from API if available (most accurate), otherwise calculate
            val amountOut = fees.outputAmount?.toLongOrNull()
                ?: ((amountLong - (fees.relayFeeTotal?.toLongOrNull() ?: 0L) - (fees.lpFeeTotal?.toLongOrNull() ?: 0L)).coerceAtLeast(0))

            val estimatedFillTime = fees.estimatedFillTimeSec ?: fees.estimatedFillTime
            android.util.Log.d("RaksaSwap", "Across: amountIn=$amount amountOut=$amountOut fee=${fees.relayFeeTotal} fillTime=${estimatedFillTime}s limits(min=${limits?.minDeposit},maxInstant=${limits?.maxDepositInstant})")

            val amountOutHuman = rawToAmount(amountOut.toString(), toDec)
            return QuoteModel(toTokenAmount = SwapAmount(amountOutHuman, toDec), allowanceContract = null, txType = ExpressTxType.SWAP).right()
        } catch (e: Exception) {
            android.util.Log.e("RaksaSwap", "Across error: ${e.message}")
            return ExpressDataError.UnknownError().left()
        }
    }

    private suspend fun buildOdosTx(
        fromAddr: String, toAddr: String, amount: String,
        fromDec: Int, toDec: Int, fromChain: String,
        userAddress: String, toAddress: String,
    ): Either<ExpressDataError, SwapDataModel> {
        try {
            val chainId = CHAIN_IDS[normalizeChainId(fromChain)] ?: 1

            // Step 1: Get quote
            val quoteResp = odosApi.getQuote(
                com.tangem.datasource.api.swap.OdosQuoteRequest(
                    chainId = chainId,
                    inputTokens = listOf(com.tangem.datasource.api.swap.OdosTokenAmount(fromAddr, amount)),
                    outputTokens = listOf(com.tangem.datasource.api.swap.OdosTokenProportion(toAddr, 1f)),
                    slippageLimitPercent = 0.5f,
                    userAddr = userAddress,
                )
            )
            android.util.Log.d("RaksaSwap", "Odos quote: pathId= out=")

            // Step 2: Assemble transaction
            val assembleResp = odosApi.assembleTransaction(
                com.tangem.datasource.api.swap.OdosAssembleRequest(
                    userAddr = userAddress,
                    pathId = quoteResp.pathId,
                    simulate = false,
                )
            )
            val tx = assembleResp.transaction ?: return ExpressDataError.UnknownError().left()
            val outAmount = assembleResp.outAmounts?.firstOrNull() ?: "0"
            val amountOut = rawToAmount(outAmount, toDec)

            android.util.Log.d("RaksaSwap", "Odos tx: to= dataLen= gas=")

            return SwapDataModel(
                toTokenAmount = SwapAmount(amountOut, toDec),
                transaction = com.tangem.feature.swap.domain.models.domain.ExpressTransactionModel.DEX(
                    fromAmount = SwapAmount(amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO, fromDec),
                    toAmount = SwapAmount(amountOut, toDec),
                    txValue = tx.value,
                    txId = "",
                    txTo = tx.to,
                    txExtraId = null,
                    txFrom = userAddress,
                    txData = tx.data,
                    otherNativeFeeWei = null,
                    gas = tx.gas.toBigInteger(),
                    allowanceContract = null,
                ),
            ).right()
        } catch (e: Exception) {
            android.util.Log.e("RaksaSwap", "Odos tx build error: ")
            return ExpressDataError.UnknownError().left()
        }
    }

    }

