package com.github.nepyh.rooter.module.user.model


/**
 * 동의 항목 식별자. DB(`user_agreements.code`)에는 이름을 그대로 문자열로 저장한다.
 * 항목을 추가하려면 `dev.conf`·`prod.conf`·`dev-s3.conf` 의 `agreements` 목록에도 같은 code 를 넣어야 기동한다
 * (목록과 이 enum 이 어긋나면 `AgreementCatalog` 가 기동 시점에 실패한다).
 */
enum class AgreementCode(val required: Boolean) {
    TERMS(required = true),
    PRIVACY(required = true),
    ;

    companion object {
        fun fromCode(code: String): AgreementCode? = entries.firstOrNull { it.name == code }
    }
}
