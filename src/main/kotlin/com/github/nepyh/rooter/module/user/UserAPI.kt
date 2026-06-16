package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.dto.UserLoginRequest
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun UserApi(userService: UserService) = ApiRoute("users") {
    post("") {
        try {
            val request = call.receive<UserRegisterRequest>()
            val response = userService.registerUser(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: UserValidationException.DuplicatedEmailException) {
            call.respond(HttpStatusCode.Conflict, mapOf("message" to e.message))
        } catch (e: UserValidationException.WrongPasswordFormatException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }
}

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