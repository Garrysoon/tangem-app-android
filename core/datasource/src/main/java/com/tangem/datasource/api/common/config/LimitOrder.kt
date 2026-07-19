package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

internal class LimitOrder : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(environment = ApiEnvironment.PROD, baseUrl = "http://localhost:8787/"),
        ApiEnvironmentConfig(environment = ApiEnvironment.MOCK, baseUrl = "http://localhost:8787/"),
    )
}
