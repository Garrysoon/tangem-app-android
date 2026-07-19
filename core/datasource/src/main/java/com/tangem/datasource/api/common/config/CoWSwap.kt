package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

internal class CoWSwap : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(environment = ApiEnvironment.PROD, baseUrl = "https://api.cow.fi/"),
        ApiEnvironmentConfig(environment = ApiEnvironment.MOCK, baseUrl = "https://api.cow.fi/"),
    )
}
