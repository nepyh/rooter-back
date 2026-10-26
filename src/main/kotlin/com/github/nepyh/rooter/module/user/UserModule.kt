package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.common.auth.JwtValidator
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.user.model.AgreementCatalog
import com.github.nepyh.rooter.module.user.model.AgreementCode
import com.github.nepyh.rooter.module.user.model.AgreementDocument
import com.github.nepyh.rooter.module.user.api.AgreementApi
import com.github.nepyh.rooter.module.user.api.AuthApi
import com.github.nepyh.rooter.module.user.api.UserApi
import org.koin.core.qualifier.named
import org.koin.dsl.module


fun UserModule(appConfig: AppConfig) = module {
    // conf 의 code 가 enum 과 어긋나면 여기서 기동이 실패한다 — 오타로 조항이 조용히 빠지는 것을 막는다
    val agreementCatalog = AgreementCatalog(
        appConfig.agreements.map { entry ->
            AgreementDocument(
                code = AgreementCode.fromCode(entry.code)
                    ?: throw IllegalStateException("알 수 없는 동의 항목 code: ${entry.code}"),
                title = entry.title,
                version = entry.version,
                url = entry.url
            )
        }
    )

    single<UserRepo> { ExposedUserRepo() }
    single<JwtValidator> { UserJwtValidator(get()) }
    single { UserService(get(), get(), agreementCatalog) }
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
    single(named("userApi")) { UserApi(get(), get()) }
    single(named("authApi")) { AuthApi(get()) }
    single(named("agreementApi")) { AgreementApi(get()) }
}
