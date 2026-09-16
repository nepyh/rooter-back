package com.github.nepyh.rooter.module.leveltest

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.leveltest.api.LevelTestApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun LevelTestModule(appConfig: AppConfig) = module {
    single { LevelTestLlmClient(appConfig) }
    single { LevelTestService(get()) }

    single(named("levelTestApi")) { LevelTestApi(get()) }
}
