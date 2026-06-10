package com.github.nepyh.rooter.module.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.dsl.module

val userModule = module {
    single { UserRepo() }
    single { UserService(get()) }
    single { UserAuthService(get(), get()) }
}

fun Application.installUserModule() {
    routing {
        route("/user") {
            configureUserAPI()
        }
    }
}