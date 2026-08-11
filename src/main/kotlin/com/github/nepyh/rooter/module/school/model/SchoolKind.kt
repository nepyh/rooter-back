package com.github.nepyh.rooter.module.school.model

/**
 * 학교 종류. 현재 서비스는 중학교 전용이지만 검색 필터로는 종류를 받는다.
 */
enum class SchoolKind(val code: String) {
    ELEMENTARY("초등학교"),
    MIDDLE("중학교"),
    HIGH("고등학교")
}
