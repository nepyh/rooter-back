package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserRegisterResponse(
    val email: String,
    val username: String
)