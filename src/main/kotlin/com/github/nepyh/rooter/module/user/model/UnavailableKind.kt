package com.github.nepyh.rooter.module.user.model

import kotlinx.serialization.Serializable

/**
 * 불가능 시간의 종류 (user_unavailable_times.kind, rooter-ddl chk_user_unavailable_times_kind).
 * - BREAKFAST / LUNCH / DINNER 는 식사. 그 요일에 등록하지 않은 식사는 서버 기본값으로 막는다 (planboard/UserRoutine.kt)
 * - ACADEMY 는 학원, CUSTOM 은 그 밖의 일정. 예전에 등록한 불가능 시간은 전부 CUSTOM
 */
@Serializable
enum class UnavailableKind {
    BREAKFAST, LUNCH, DINNER, ACADEMY, CUSTOM;

    val isMeal: Boolean get() = this == BREAKFAST || this == LUNCH || this == DINNER
}
