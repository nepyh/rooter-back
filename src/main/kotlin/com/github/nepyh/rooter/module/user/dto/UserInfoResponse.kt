package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserInfoResponse(
    val id: Int,
    val username: String,
    val email: String,
    val schoolId: String? = null,    // 학생 프로필(POST /users/{id}/profile) 등록 전이면 null
    val grade: Int? = null,
    val classNumber: Int? = null,
    val createdAt: String,
    val avatarImageKey: String? = null,
    val bio: String? = null
)