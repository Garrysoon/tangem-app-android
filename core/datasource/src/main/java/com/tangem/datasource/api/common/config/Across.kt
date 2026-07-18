package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig

internal class Across : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = getInitialEnvironment()
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(environment = ApiEnvironment.PROD, baseUrl = "https://across.to/api/"),
        ApiEnvironmentConfig(environment = ApiEnvironment.MOCK, baseUrl = "https://across.to/api/"),
    )
    private fun getInitialEnvironment(): ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
}
