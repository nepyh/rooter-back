package com.github.nepyh.rooter.module.school.model

import com.github.nepyh.rooter.module.school.exception.NiceApiException
import kotlinx.serialization.Serializable

/**
 * 학교 검색 결과 도메인 모델.
 * schoolId 는 NICE 호출에 필요한 교육청 코드 + 학교 코드를 합친 합성 식별자.
 * (예: "C10" + "7181084" = "C107181084" — 10자, student_profiles.school_id char(10) 호환)
 */
@Serializable
data class School(
    val schoolId: String,
    val officeCode: String,
    val officeName: String,
    val schoolCode: String,
    val name: String,
    val kind: SchoolKind,
    val region: String,
    val foundation: String?
) {
    companion object {
        /** NICE 교육청 코드(ATPT_OFCDC_SC_CODE) 길이 — 시도교육청 3자리 */
        const val OFFICE_CODE_LENGTH = 3

        /** NICE 학교 코드(SD_SCHUL_CODE) 길이 — 7자리 숫자 */
        const val SCHOOL_CODE_LENGTH = 7

        fun of(
            officeCode: String,
            officeName: String,
            schoolCode: String,
            name: String,
            kind: SchoolKind,
            region: String,
            foundation: String?
        ): School = School(
            schoolId = compositeId(officeCode, schoolCode),
            officeCode = officeCode,
            officeName = officeName,
            schoolCode = schoolCode,
            name = name,
            kind = kind,
            region = region,
            foundation = foundation
        )

        /** 교육청 코드 + 학교 코드를 합쳐 10자 schoolId 를 만든다. */
        fun compositeId(officeCode: String, schoolCode: String): String = officeCode + schoolCode

        /** 합성 schoolId 를 (교육청 코드, 학교 코드) 로 분리한다. */
        fun splitSchoolId(schoolId: String): Pair<String, String> {
            if (schoolId.length != OFFICE_CODE_LENGTH + SCHOOL_CODE_LENGTH) {
                throw NiceApiException.BadRequestException("잘못된 schoolId 형식입니다: $schoolId")
            }
            return schoolId.substring(0, OFFICE_CODE_LENGTH) to schoolId.substring(OFFICE_CODE_LENGTH)
        }
    }
}
