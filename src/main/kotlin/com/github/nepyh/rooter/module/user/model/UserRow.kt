package com.github.nepyh.rooter.module.user.model

import java.time.LocalDateTime

data class UserRow(
    val id: Int,
    val email: String,
    val userName: String,
    val password: String,
    val avatarImageKey: String?,
    val bio: String?,
    val createdAt: LocalDateTime
)