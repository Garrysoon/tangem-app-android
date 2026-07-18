package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

/**
 * Raksa DEX Aggregator ApiConfig
 * @see <a href="https://github.com/Garrysoon/raksa-dex-aggregator">Raksa backend</a>
 */
internal class Raksa : ApiConfig() {

    override val defaultEnvironment: ApiEnvironment = getInitialEnvironment()

    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        createDevEnvironment(),
        createProdEnvironment(),
    )

    private fun getInitialEnvironment(): ApiEnvironment {
        return when (BuildConfig.BUILD_TYPE) {
            MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
            DEBUG_BUILD_TYPE, INTERNAL_BUILD_TYPE, EXTERNAL_BUILD_TYPE -> ApiEnvironment.DEV
            RELEASE_BUILD_TYPE -> ApiEnvironment.PROD
            else -> error("Unknown build type [${BuildConfig.BUILD_TYPE}]")
        }
    }

    private fun createDevEnvironment(): ApiEnvironmentConfig {
        return ApiEnvironmentConfig(
            environment = ApiEnvironment.DEV,
            baseUrl = "http://10.0.2.2:8787/",  // Android emulator localhost
        )
    }

    private fun createProdEnvironment(): ApiEnvironmentConfig {
        return ApiEnvironmentConfig(
            environment = ApiEnvironment.PROD,
            baseUrl = "http://localhost:8787/",  // Override in production
        )
    }
}
