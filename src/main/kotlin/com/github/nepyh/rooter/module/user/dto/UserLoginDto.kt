package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable


@Serializable
data class UserLoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class UserLoginResponse(
    val userId: Int,
    val email: String,
    val username: String,
    val token: String,
    // 필수 동의 항목 중 아직 유효한 동의가 없는 code. 비어 있으면 동의 화면을 띄우지 않는다
    val pendingAgreements: List<String> = emptyList()
)

@Serializable
data class SocialLoginRequest(
    val idToken: String // Google/Apple 네이티브 SDK 로 발급받은 id_token
)
