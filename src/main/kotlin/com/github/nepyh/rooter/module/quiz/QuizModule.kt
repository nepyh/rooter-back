package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.quiz.api.QuizApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun QuizModule(appConfig: AppConfig) = module {
    single { QuizLlmClient(appConfig) }
    single { QuizService(get()) }

    single(named("quizApi")) { QuizApi(get()) }
}
