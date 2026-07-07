package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.ApiRoute
import org.koin.core.qualifier.named
import org.koin.dsl.module

val planBoardModule = module {
    single { PlanBoardService() }
    single { PlanTaskService() }

    single<ApiRoute>(named("planBoardApi")) {
        val api = PlanBoardApi(get<PlanBoardService>())
        ApiRoute { with(api) { registerRoutes() } }
    }

    single<ApiRoute>(named("planTaskApi")) {
        val api = PlanTaskApi(get<PlanTaskService>())
        ApiRoute { with(api) { registerRoutes() } }
    }
}