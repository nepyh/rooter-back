package com.github.nepyh.rooter.module.user

import org.koin.core.qualifier.named
import org.koin.dsl.module

val userModule = module {
    single { UserRepo() }
    single { UserService(get()) }
    single { UserAuthService(get(), get()) }
    single(named("apiRoute")) { UserApi(get()) }
    single(named("apiRoute")) { AuthApi(get()) }
}