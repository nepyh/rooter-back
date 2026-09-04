package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.planboard.api.CatalogApi
import com.github.nepyh.rooter.module.planboard.api.DailyPlanApi
import com.github.nepyh.rooter.module.planboard.api.PlanBoardApi
import com.github.nepyh.rooter.module.planboard.api.PlanGenerationApi
import com.github.nepyh.rooter.module.planboard.api.PlanTaskApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun PlanBoardModule(appConfig: AppConfig) = module {
    single { PlanBoardService() }
    single { PlanTaskService() }
    single { CatalogService() }
    single { PlanGenerationLlmClient(appConfig) }
    single { PlanGenerationService(get()) }

    single(named("planBoardApi")) { PlanBoardApi(get()) }
    single(named("planTaskApi")) { PlanTaskApi(get()) }
    single(named("catalogApi")) { CatalogApi(get()) }
    single(named("dailyPlanApi")) { DailyPlanApi(get()) }
    single(named("planGenerationApi")) { PlanGenerationApi(get()) }
}
