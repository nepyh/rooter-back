package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable


@Serializable
data class UserLoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class UserLoginResponse(
    val email: String,
    val username: String,
    val token: String
)

@Serializable
data class SocialLoginRequest(
    val idToken: String // Google/Apple 네이티브 SDK 로 발급받은 id_token
)
