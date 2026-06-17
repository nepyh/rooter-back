package com.github.nepyh.rooter.module.example

import org.koin.core.qualifier.named
import org.koin.dsl.module


val exampleModule = module {
    single { ExampleService() }
    single(named("exampleApi")) { ExampleApi(get()) }
}
