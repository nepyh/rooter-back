package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.api.AuthApi
import com.github.nepyh.rooter.module.user.api.UserApi
import org.koin.core.qualifier.named
import org.koin.dsl.module

val userModule = module {
    single { UserRepo() }
    single { UserService(get()) }
    single { AuthService(get(), get()) }
    single(named("userApi")) { UserApi(get()) }
    single(named("authApi")) { AuthApi(get()) }
}
