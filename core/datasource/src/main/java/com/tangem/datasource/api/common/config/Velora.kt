package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

internal class Velora : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(environment = ApiEnvironment.PROD, baseUrl = "https://api.velora.xyz/"),
        ApiEnvironmentConfig(environment = ApiEnvironment.MOCK, baseUrl = "https://api.velora.xyz/"),
    )
}
