package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.common.auth.JwtValidator
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.user.api.AuthApi
import com.github.nepyh.rooter.module.user.api.UserApi
import org.koin.core.qualifier.named
import org.koin.dsl.module


fun UserModule(appConfig: AppConfig) = module {
    single { UserRepo() }
    single<JwtValidator> { UserJwtValidator(get()) }
    single { UserService(get(), get()) }
    single {
        AuthService(
            get(),
            get(),
            appConfig.jwtSecret,
            appConfig.jwtIssuer,
            appConfig.googleClientId,
            appConfig.appleClientId
        )
    }
    single(named("userApi")) { UserApi(get()) }
    single(named("authApi")) { AuthApi(get()) }
}
