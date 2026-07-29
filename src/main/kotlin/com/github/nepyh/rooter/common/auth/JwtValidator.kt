package com.github.nepyh.rooter.common.auth

import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.JWTPrincipal

interface JwtValidator {
    fun validate(credential: JWTCredential): JWTPrincipal?
}
