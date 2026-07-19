package com.tangem.datasource.api.common.config

import com.tangem.datasource.BuildConfig
import com.tangem.utils.ProviderSuspend

internal class Odos : ApiConfig() {
    override val defaultEnvironment: ApiEnvironment = when (BuildConfig.BUILD_TYPE) {
        MOCKED_BUILD_TYPE -> ApiEnvironment.MOCK
        else -> ApiEnvironment.PROD
    }
    override val environmentConfigs: List<ApiEnvironmentConfig> = listOf(
        ApiEnvironmentConfig(
            environment = ApiEnvironment.PROD,
            baseUrl = "https://api.odos.xyz/",
            headers = mapOf("X-API-Key" to ProviderSuspend { "dfc05496-3cd7-41f3-9c95-7d694d239dc2" }),
        ),
        ApiEnvironmentConfig(
            environment = ApiEnvironment.MOCK,
            baseUrl = "https://api.odos.xyz/",
            headers = mapOf("X-API-Key" to ProviderSuspend { "dfc05496-3cd7-41f3-9c95-7d694d239dc2" }),
        ),
    )
}
