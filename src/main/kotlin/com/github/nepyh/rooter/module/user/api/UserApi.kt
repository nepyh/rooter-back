package com.github.nepyh.rooter.module.user.api

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.module.user.UserService
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
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
        } catch (e: UserValidationException.WrongUsernameException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to e.message))           // 400
        } catch (e: UserValidationException.DuplicatedEmailException) {
            call.respond(HttpStatusCode.Conflict, mapOf("message" to e.message))              // 409
        } catch (e: UserValidationException.WrongUsernameAlreadyException) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to e.message))    // 422
        } catch (e: UserValidationException.WrongPasswordFormatException) {
            call.respond(HttpStatusCode.NotAcceptable, mapOf("message" to e.message))          // 406
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))  // 500
        }
    }

    get("{id}") {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val response = userService.getUserInfo(id)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }

    get("{id}/unavailable-times") {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val response = userService.getUnavailableTimes(id)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }

    post("{id}/profile") {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val request = call.receive<StudentProfileRequest>()
            val response = userService.createStudentProfile(id, request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }

    post("{id}/unavailable-times") {
        try {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "유효하지 않은 ID입니다."))
            val request = call.receive<UnavailableTimeRequest>()
            val response = userService.addUnavailableTime(id, request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: UserNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "서버 오류가 발생했습니다."))
        }
    }
}