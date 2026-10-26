import com.github.nepyh.rooter.module.user.model.AgreementCatalog
import com.github.nepyh.rooter.module.user.model.AgreementCode
import com.github.nepyh.rooter.module.user.model.AgreementDocument


/** 테스트에서 쓰는 현재 조항 목록. prod conf 와 같은 code·버전 조합을 유지한다. */
const val TEST_AGREEMENT_VERSION = "2026-11-01"

val testAgreementCatalog = AgreementCatalog(
    listOf(
        AgreementDocument(
            code = AgreementCode.TERMS,
            title = "이용약관",
            version = TEST_AGREEMENT_VERSION,
            url = "https://example.test/terms/$TEST_AGREEMENT_VERSION/"
        ),
        AgreementDocument(
            code = AgreementCode.PRIVACY,
            title = "개인정보 처리방침",
            version = TEST_AGREEMENT_VERSION,
            url = "https://example.test/privacy/$TEST_AGREEMENT_VERSION/"
        )
    )
)
