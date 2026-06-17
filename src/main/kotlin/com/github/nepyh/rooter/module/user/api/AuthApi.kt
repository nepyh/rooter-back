package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.UserAuthService
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post


fun AuthApi(userAuthService: UserAuthService) = ApiRoute("auth") {
    post("login") {
        try {
            val request = call.receive<UserLoginRequest>()
            val response = userAuthService.login(request)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
        } catch (e: UserValidationException.WrongPasswordException) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }
}
