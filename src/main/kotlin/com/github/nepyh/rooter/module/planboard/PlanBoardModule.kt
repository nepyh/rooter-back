package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.api.PlanBoardApi
import com.github.nepyh.rooter.module.planboard.api.PlanTaskApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun PlanBoardModule() = module {
    single { PlanBoardService() }
    single { PlanTaskService() }

    single(named("planBoardApi")) { PlanBoardApi(get()) }
    single(named("planTaskApi")) { PlanTaskApi(get()) }
}