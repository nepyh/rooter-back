package com.github.nepyh.rooter.module.example

import org.koin.dsl.module


val exampleModule = module {
    single { ExampleService() }
    single { ExampleApi(get()) }
}
