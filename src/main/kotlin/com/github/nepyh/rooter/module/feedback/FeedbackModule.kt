package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.feedback.api.FeedbackApi
import com.github.nepyh.rooter.module.feedback.api.TaskFeedbackApi
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun FeedbackModule(appConfig: AppConfig) = module {
    single { ReplanLlmClient(appConfig) }
    single { FeedbackService(get()) }

    single(named("feedbackApi")) { FeedbackApi(get()) }
    single(named("taskFeedbackApi")) { TaskFeedbackApi(get()) }

    // named 필수: SchedulerJob 을 이름 없이 등록하면 다른 모듈의 SchedulerJob 정의를 덮어써서
    // SchedulerEngine 의 getAll<SchedulerJob>() 에 하나만 잡히는 문제가 생김
    single<SchedulerJob>(named("feedbackReplanJob")) { FeedbackReplanJob(get()) }
}
