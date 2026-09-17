package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.taskquiz.api.TaskQuizApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun TaskQuizModule(appConfig: AppConfig) = module {
    single { TaskQuizLlmClient(appConfig) }
    single { TaskQuizService(get()) }
    // named 필수: SchedulerJob 을 이름 없이 등록하면 다른 모듈의 SchedulerJob 정의를 덮어써서
    // SchedulerEngine 의 getAll<SchedulerJob>() 에 하나만 잡히는 문제가 생김
    single<SchedulerJob>(named("taskQuizTriggerJob")) { TaskQuizTriggerJob(get()) }

    single(named("taskQuizApi")) { TaskQuizApi(get()) }
}
