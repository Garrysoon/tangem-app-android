package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

internal class LiFi : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(environment = ApiEnvironment.PROD, baseUrl = "https://li.quest/"),
        ApiEnvironmentConfig(environment = ApiEnvironment.MOCK, baseUrl = "https://li.quest/"),
    )
}

/** LI.FI Intents API for TRON and cross-chain intent-based swaps */
internal class LiFiIntents : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(environment = ApiEnvironment.PROD, baseUrl = "https://order.li.fi/"),
        ApiEnvironmentConfig(environment = ApiEnvironment.MOCK, baseUrl = "https://order-dev.li.fi/"),
    )
}