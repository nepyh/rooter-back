package com.github.nepyh.rooter.module.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.dsl.module

val userModule = module {
    single { UserRepo() }
    single { UserService(get()) }
    single { UserAuthService(get(), get())}
}

fun Application.installUserModule() {
    routing {
        route("/users") {
            configureUserAPI()  // POST /users → 회원가입
        }
        route("/auth") {
            configureAuthAPI()  // POST /auth/login → 로그인
        }
    }
}