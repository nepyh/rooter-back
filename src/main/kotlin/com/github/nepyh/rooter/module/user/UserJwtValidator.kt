package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.common.auth.JwtValidator
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.JWTPrincipal

class UserJwtValidator(private val userRepo: UserRepo) : JwtValidator {
    override fun validate(credential: JWTCredential): JWTPrincipal? {
        val userId = credential.payload.getClaim("userId").asInt()
        val tokenVersion = credential.payload.getClaim("tokenVersion").asInt()
        val user = userId?.let { userRepo.findUserById(it) }

        return if (user != null && tokenVersion == user.tokenVersion) {
            JWTPrincipal(credential.payload)
        } else {
            null
        }
    }
}
