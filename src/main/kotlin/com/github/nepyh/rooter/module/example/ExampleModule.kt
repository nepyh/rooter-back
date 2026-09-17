package com.github.nepyh.rooter.module.example

import org.koin.core.qualifier.named
import org.koin.dsl.module


fun ExampleModule() = module {
    single { ExampleService() }
    single(named("exampleApi")) { ExampleApi(get()) }

    // scheduler 데모 잡 (dev 전용)
    // 이름 없이 single<SchedulerJob> {} 을 여러 모듈에서 선언하면 Koin이 같은 정의 슬롯으로 취급해서
    // 나중에 등록된 쪽이 앞선 걸 덮어써버림 (getAll()에서 하나만 잡힘) -> 반드시 named 로 구분해야 함
    single<com.github.nepyh.rooter.module.scheduler.SchedulerJob>(named("exampleSchedulerJob")) { ExampleSchedulerJob() }
}
