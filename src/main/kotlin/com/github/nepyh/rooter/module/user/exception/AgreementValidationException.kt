package com.github.nepyh.rooter.module.user.exception

import io.ktor.http.HttpStatusCode


sealed class AgreementValidationException(
    val status: HttpStatusCode,
    val code: String,
    message: String
) : Exception(message) {
    class UnknownCodeException(unknownCode: String) : AgreementValidationException(
        HttpStatusCode.BadRequest,
        "UNKNOWN_AGREEMENT_CODE",
        "알 수 없는 동의 항목입니다: $unknownCode"
    )
    class DuplicatedCodeException(duplicatedCode: String) : AgreementValidationException(
        HttpStatusCode.BadRequest,
        "DUPLICATED_AGREEMENT_CODE",
        "같은 동의 항목을 두 번 보냈습니다: $duplicatedCode"
    )
    class VersionMismatchException(agreementCode: String, currentVersion: String, requestedVersion: String) :
        AgreementValidationException(
            HttpStatusCode.BadRequest,
            "AGREEMENT_VERSION_MISMATCH",
            "$agreementCode 의 버전이 현재 버전과 다릅니다. (현재 $currentVersion, 요청 $requestedVersion)"
        )
    class RequiredMissingException(missingCodes: List<String>) : AgreementValidationException(
        HttpStatusCode.BadRequest,
        "REQUIRED_AGREEMENT_MISSING",
        "필수 동의 항목에 동의해야 합니다: ${missingCodes.joinToString(", ")}"
    )
    class EmptyAgreementsException : AgreementValidationException(
        HttpStatusCode.BadRequest,
        "EMPTY_AGREEMENTS",
        "동의 항목이 비어 있습니다."
    )
}
