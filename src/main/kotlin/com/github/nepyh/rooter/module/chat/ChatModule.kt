package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.chat.api.ChatApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

fun ChatModule(appConfig: AppConfig) = module {
    single { ChatLlmClient(appConfig) }
    single { ChatService(get()) }

    single(named("chatApi")) { ChatApi(get()) }
}
