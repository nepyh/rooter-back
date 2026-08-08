package com.github.nepyh.rooter.module.example

import org.koin.core.qualifier.named
import org.koin.dsl.module


fun ExampleModule() = module {
    single { ExampleService() }
    single(named("exampleApi")) { ExampleApi(get()) }

    // scheduler 데모 잡 (dev 전용)
    single<com.github.nepyh.rooter.module.scheduler.SchedulerJob> { ExampleSchedulerJob() }
}
