package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.common.ApiRoute
import org.koin.core.qualifier.named
import org.koin.dsl.module

val userModule = module {
    single { UserRepo() }
    single { UserService(get()) }
    single { UserAuthService(get(), get()) }
    single(named("userApi")) { UserApi(get()) }
    single(named("authApi")) { AuthApi(get()) }
}