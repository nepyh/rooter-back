package com.github.nepyh.rooter.module

import com.github.nepyh.rooter.module.blink.blinkModule
import com.github.nepyh.rooter.module.blink.installBlinkModule
import com.github.nepyh.rooter.module.health.installHealthModule
import com.github.nepyh.rooter.module.user.userModule
import com.github.nepyh.rooter.module.user.installUserModule
import io.ktor.server.application.Application
import org.koin.dsl.module

val appModule = module {
    includes(blinkModule)
    includes(userModule)
}

fun Application.installAppModule() {
    installBlinkModule()
    installHealthModule()
    installUserModule()
}
