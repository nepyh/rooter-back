package com.github.nepyh.rooter.module.school

import com.github.nepyh.rooter.common.config.AppConfig
import org.koin.dsl.module

fun SchoolModule(appConfig: AppConfig) = module {
    single { NiceApiClient(apiKey = appConfig.niceApiKey, baseUrl = appConfig.niceBaseUrl) }
    single { SchoolDataFetcher(get()) }
}
