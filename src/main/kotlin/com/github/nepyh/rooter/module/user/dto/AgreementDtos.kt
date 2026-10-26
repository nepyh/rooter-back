package com.github.nepyh.rooter.module.user.dto

import kotlinx.serialization.Serializable


/** 가입·재동의 요청에 실리는 조항 1건의 결정. 거부·철회는 `agreed=false` 로 보낸다. */
@Serializable
data class AgreementDecision(
    val code: String,
    val version: String,
    val agreed: Boolean
)

@Serializable
data class AgreementDocumentResponse(
    val code: String,
    val required: Boolean,
    val title: String,
    val version: String,
    val url: String
)

@Serializable
data class AgreementCatalogResponse(
    val agreements: List<AgreementDocumentResponse>
)

@Serializable
data class UserAgreementResponse(
    val code: String,
    val required: Boolean,
    val version: String,
    val agreed: Boolean,
    val createdAt: String
)

@Serializable
data class UserAgreementsResponse(
    val agreements: List<UserAgreementResponse>
)

@Serializable
data class AgreementsUpdateRequest(
    val agreements: List<AgreementDecision>
)

/** 남은 필수 동의 항목. 비어 있으면 동의 화면을 띄울 필요가 없다. */
@Serializable
data class AgreementsUpdateResponse(
    val pendingAgreements: List<String>
)
