package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.module.feedback.api.FeedbackApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun FeedbackModule() = module {
    single { FeedbackService() }

    single(named("feedbackApi")) { FeedbackApi(get()) }
}
