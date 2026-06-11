package com.github.nepyh.rooter.module.example

import org.koin.dsl.module


val blinkModule = module {
    single { ExampleService() }
    single { BlinkApi() }
}
