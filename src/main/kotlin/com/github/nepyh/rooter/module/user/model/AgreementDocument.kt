package com.github.nepyh.rooter.module.user.model


/** 지금 게시 중인 조항 한 건. 값은 전부 conf 에서 오고, 문서 본문은 [url] 이 가리키는 정적 페이지가 정본이다. */
data class AgreementDocument(
    val code: AgreementCode,
    val title: String,
    val version: String,
    val url: String
)

/**
 * 현재 조항 목록. conf 에서 만든 값을 그대로 들고 있으며, 목록이 [AgreementCode] 와 어긋나면
 * 기동 시점에 IllegalStateException 으로 죽는다 — code 오타가 조용히 "그 조항은 영원히 동의 대상 아님" 이 되는 것을 막는다.
 */
class AgreementCatalog(documents: List<AgreementDocument>) {

    val documents: List<AgreementDocument> = documents.toList()

    private val byCode: Map<AgreementCode, AgreementDocument>

    init {
        if (this.documents.isEmpty()) {
            throw IllegalStateException("agreements 설정이 비어 있습니다 (dev.conf / prod.conf 확인)")
        }
        val duplicated = this.documents.groupBy { it.code }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw IllegalStateException("agreements 설정에 중복된 code 가 있습니다: $duplicated")
        }
        val missing = AgreementCode.entries.filter { code -> this.documents.none { it.code == code } }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("agreements 설정에 빠진 code 가 있습니다: $missing")
        }
        byCode = this.documents.associateBy { it.code }
    }

    fun current(code: AgreementCode): AgreementDocument = byCode.getValue(code)

    fun all(): List<AgreementDocument> = documents

    /** 필수 동의 항목의 현재 버전. 응답의 pendingAgreements 판정에 쓴다. */
    fun requiredCodes(): List<AgreementCode> = AgreementCode.entries.filter { it.required }
}
