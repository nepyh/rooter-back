package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable


@Serializable
data class UserRegisterRequest(
    val email: String,
    val username: String,
    val password: String,
    // 구버전 앱은 안 보낸다. 없으면 가입만 하고 필수 동의는 pendingAgreements 로 나중에 받는다
    val agreements: List<AgreementDecision>? = null
)

@Serializable
data class UserRegisterResponse(
    val email: String,
    val username: String
)
