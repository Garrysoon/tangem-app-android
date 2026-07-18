package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

internal class Paraswap : ApiConfig() {

    override val defaultEnvironment: ApiEnvironment = getInitialEnvironment()

    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        createProdEnvironment(),
        createMockEnvironment(),
    )

    private fun getInitialEnvironment(): ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }

    private fun createProdEnvironment() = ApiEnvironmentConfig(
        environment = ApiEnvironment.PROD,
        baseUrl = "https://api.paraswap.io/",
    )

    private fun createMockEnvironment() = ApiEnvironmentConfig(
        environment = ApiEnvironment.MOCK,
        baseUrl = "https://api.paraswap.io/",
    )
}
