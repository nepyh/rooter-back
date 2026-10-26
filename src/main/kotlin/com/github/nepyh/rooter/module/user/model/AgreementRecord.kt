package com.github.nepyh.rooter.module.user.model


/**
 * `user_agreements` 에 남길 동의 1건. `required` 는 **동의 시점의 필수 여부 스냅샷**이라
 * 나중에 그 항목이 선택으로 바뀌어도 "그때 필수였는데 동의했다" 는 사실이 남는다.
 */
data class AgreementRecord(
    val code: AgreementCode,
    val version: String,
    val required: Boolean,
    val agreed: Boolean
)


/** 조회용 — code 별 가장 최근 동의 1건. */
data class AgreementState(
    val code: AgreementCode,
    val version: String,
    val required: Boolean,
    val agreed: Boolean,
    val createdAt: String
)
