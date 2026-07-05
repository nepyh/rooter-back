package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable

@Serializable
data class AvatarUpdateResponse(
    val userId: Int,
    val avatarImageKey: String
)
