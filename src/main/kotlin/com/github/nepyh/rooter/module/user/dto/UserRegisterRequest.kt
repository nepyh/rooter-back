package com.github.nepyh.rooter.module.user.dto

data class UserRegisterRequest(
    val email: String,
    val username: String,
    val password: String
)