package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable


@Serializable
data class UserRegisterRequest(
    val email: String,
    val username: String,
    val password: String
)

@Serializable
data class UserRegisterResponse(
    val email: String,
    val username: String
)
