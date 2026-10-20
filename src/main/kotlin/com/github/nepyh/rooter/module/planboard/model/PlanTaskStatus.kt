package com.github.nepyh.rooter.module.planboard.model

import kotlinx.serialization.Serializable

/**
 * 할일 상태. DB(plan_tasks.status)에는 이름 그대로 저장한다 (rooter-ddl #36).
 * - DONE 은 태스크 퀴즈 통과로만 된다 (사용자가 직접 완료로 바꿀 수 없음)
 * - FAILED 는 사용자가 실패를 고르거나 태스크 퀴즈를 3회 모두 떨어졌을 때. 한 번 실패하면 되돌릴 수 없다
 */
@Serializable
enum class PlanTaskStatus {
    PENDING, DONE, FAILED
}
