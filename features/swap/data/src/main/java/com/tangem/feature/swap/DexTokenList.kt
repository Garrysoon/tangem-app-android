package com.tangem.feature.swap

/**
 * Hardcoded DEX token list per chain.
 * Ported from Raksa backend chains.py.
 */
object DexTokenList {

    const val NATIVE_TOKEN = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
    const val ZERO_TOKEN = "0x0000000000000000000000000000000000000000"

    data class DexToken(
        val symbol: String,
        val address: String,
        val decimals: Int,
    )

    val ethereum = listOf(
        DexToken("ETH", NATIVE_TOKEN, 18),
        DexToken("WETH", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", 18),
        DexToken("USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6),
        DexToken("USDT", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6),
        DexToken("DAI", "0x6B175474E89094C44Da98b954EedeAC495271d0F", 18),
        DexToken("WBTC", "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", 8),
    )

    val arbitrum = listOf(
        DexToken("ETH", NATIVE_TOKEN, 18),
        DexToken("WETH", "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", 18),
        DexToken("USDC", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", 6),
        DexToken("USDC.e", "0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8", 6),
        DexToken("USDT", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 6),
        DexToken("DAI", "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", 18),
        DexToken("WBTC", "0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f", 8),
        DexToken("ARB", "0x912CE59144191C1204E64559FE8253a0e49E6548", 18),
    )

    val optimism = listOf(
        DexToken("ETH", NATIVE_TOKEN, 18),
        DexToken("WETH", "0x4200000000000000000000000000000000000006", 18),
        DexToken("USDC", "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", 6),
        DexToken("USDT", "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", 6),
        DexToken("DAI", "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", 18),
        DexToken("WBTC", "0x68f180fcCe6836688e9084f035309E29Bf0A2095", 8),
        DexToken("OP", "0x4200000000000000000000000000000000000042", 18),
    )

    val base = listOf(
        DexToken("ETH", NATIVE_TOKEN, 18),
        DexToken("WETH", "0x4200000000000000000000000000000000000006", 18),
        DexToken("USDC", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6),
        DexToken("USDbC", "0xd9aAEc86B65D86f6A7B5B1b0c42FFA531710b6CA", 6),
        DexToken("DAI", "0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb", 18),
        DexToken("WBTC", "0x0555E30da8f98308EdB960aa94C0Db47230d2B9c", 8),
    )

    val polygon = listOf(
        DexToken("MATIC", NATIVE_TOKEN, 18),
        DexToken("WMATIC", "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270", 18),
        DexToken("USDC", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", 6),
        DexToken("USDC.e", "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174", 6),
        DexToken("USDT", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", 6),
        DexToken("DAI", "0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063", 18),
        DexToken("WETH", "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", 18),
        DexToken("WBTC", "0x1BFD67037B42CF73acF2047067bd4F2C47D9BfD6", 8),
    )

    val bsc = listOf(
        DexToken("BNB", NATIVE_TOKEN, 18),
        DexToken("WBNB", "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c", 18),
        DexToken("USDC", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18),
        DexToken("USDT", "0x55d398326f99059fF775485246999027B3197955", 18),
        DexToken("WETH", "0x2170Ed0880ac9A755fd29B2688956BD959F933F8", 18),
        DexToken("WBTC", "0x0555E30da8f98308EdB960aa94C0Db47230d2B9c", 8),
    )

    /** Chain slug -> chain ID for EVM chains */
    val chainIds = mapOf(
        "ethereum" to 1,
        "arbitrum" to 42161,
        "optimism" to 10,
        "base" to 8453,
        "polygon" to 137,
        "bsc" to 56,
    )

    /** KyberSwap uses chain slug names */
    val kyberChainSlugs = mapOf(
        "ethereum" to "ethereum",
        "arbitrum" to "arbitrum",
        "optimism" to "optimism",
        "base" to "base",
        "polygon" to "polygon",
        "bsc" to "bsc",
    )

    fun getTokens(chain: String): List<DexToken> = when (chain.lowercase()) {
        "ethereum" -> ethereum
        "arbitrum" -> arbitrum
        "optimism" -> optimism
        "base" -> base
        "polygon" -> polygon
        "bsc" -> bsc
        else -> emptyList()
    }
}
