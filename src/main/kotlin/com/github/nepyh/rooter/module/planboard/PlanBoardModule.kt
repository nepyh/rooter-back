package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.ApiRoute // 👈 이거 임포트 꼭 확인!
import org.koin.core.qualifier.named
import org.koin.dsl.module

val planBoardModule = module {
    single { PlanBoardService() }

    // 🆕 ApiRoute 블록으로 감싸고, 그 안에서 registerRoutes()를 호출해 줍니다!
    single(named("planBoardApi")) {
        val api = PlanBoardApi(get())
        ApiRoute {
            with(api) { registerRoutes() }
        }
    }
}