package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
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
        } catch (e: UserValidationException.WrongUsernameAlreadyException) {
            call.respond(HttpStatusCode.Conflict, mapOf("message" to e.message))
        }
    }
}
