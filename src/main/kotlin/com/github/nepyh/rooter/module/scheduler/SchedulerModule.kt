package com.github.nepyh.rooter.module.scheduler

import org.koin.dsl.module

fun SchedulerModule() = module {
    single { JobRunRepo() }
    single { SchedulerEngine(getAll<SchedulerJob>(), get()) }
}
