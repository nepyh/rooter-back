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

@Serializable
data class PasswordUpdateResponse(
    val message: String
)
