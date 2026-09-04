package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.feedback.api.FeedbackApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun FeedbackModule(appConfig: AppConfig) = module {
    single { ReplanLlmClient(appConfig) }
    single { FeedbackService(get()) }

    single(named("feedbackApi")) { FeedbackApi(get()) }
}
