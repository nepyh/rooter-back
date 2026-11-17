package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val username: String? = null,
    val bio: String? = null
)

@Serializable
data class UserProfileUpdateResponse(
    val id: Int,
    val username: String,
    val bio: String?
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

/** 비밀번호 변경 첫 단계에서 현재 비밀번호만 맞춰 본다 (#364) */
@Serializable
data class VerifyPasswordRequest(
    val currentPassword: String
)

@Serializable
data class PasswordUpdateResponse(
    val message: String,
    val token: String // 기존 토큰은 모두 무효화되므로, 이 기기는 새 토큰으로 교체해서 써야 함
)
