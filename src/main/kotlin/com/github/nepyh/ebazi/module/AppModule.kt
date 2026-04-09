package com.github.nepyh.ebazi.module

import com.github.nepyh.ebazi.module.blink.blinkModule
import com.github.nepyh.ebazi.module.blink.installBlinkModule
import io.ktor.server.application.Application
import org.koin.dsl.module

val appModule = module {
    includes(blinkModule)
}

fun Application.installAppModule() {
    installBlinkModule()
}