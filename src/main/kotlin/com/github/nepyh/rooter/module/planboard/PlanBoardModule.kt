package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.planboard.api.BusyTimeApi
import com.github.nepyh.rooter.module.planboard.api.CatalogApi
import com.github.nepyh.rooter.module.planboard.api.DailyPlanApi
import com.github.nepyh.rooter.module.planboard.api.PlanBoardApi
import com.github.nepyh.rooter.module.planboard.api.PlanGenerationApi
import com.github.nepyh.rooter.module.planboard.api.PlanTaskApi
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun PlanBoardModule(appConfig: AppConfig) = module {
    single { PlanBoardService() }
    single { PlanTaskService() }
    single { CatalogService(get()) }
    single { PlanGenerationLlmClient(appConfig) }
    single {
        PlanGenerationService(get(), get(), notificationService = get()).also { service ->
            // 앞 구간을 응답한 뒤 남은 구간을 바로 이어서 만든다 (#405). Continuer 가 이 서비스에 기대므로 부를 때 꺼낸다
            service.onGenerating = { boardId -> get<PlanGenerationContinuer>().kick(boardId) }
        }
    }
    single { PlanGenerationContinueJob(get()) }
    // named 필수: SchedulerJob 을 이름 없이 등록하면 다른 모듈의 SchedulerJob 정의를 덮어써서
    // SchedulerEngine 의 getAll<SchedulerJob>() 에 하나만 잡히는 문제가 생김
    single<SchedulerJob>(named("planGenerationContinueJob")) { get<PlanGenerationContinueJob>() }
    single { PlanGenerationContinuer(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
    single { BusyTimeService(get()) }

    single(named("planBoardApi")) { PlanBoardApi(get()) }
    single(named("planTaskApi")) { PlanTaskApi(get()) }
    single(named("catalogApi")) { CatalogApi(get()) }
    single(named("dailyPlanApi")) { DailyPlanApi(get()) }
    single(named("planGenerationApi")) { PlanGenerationApi(get()) }
    single(named("busyTimeApi")) { BusyTimeApi(get()) }
}
