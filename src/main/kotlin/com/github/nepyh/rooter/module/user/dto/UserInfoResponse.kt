package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserInfoResponse(
    val id: Int,
    val username: String,
    val email: String,
    val schoolId: String,
    val grade: Int,
    val classNumber: Int,
    val createdAt: String
)