package com.github.nepyh.rooter.module.planboard.model

/**
 * AI 계획 생성 상태 (rooter-ddl `plan_boards.generation_status`, #405).
 * 앞 구간만 먼저 만들어 응답하고 나머지 기간은 서버가 이어서 만든다. 응답에는 [name] 을 그대로 쓴다.
 */
enum class PlanGenerationStatus(val value: String) {
    COMPLETED("completed"), // 다 만듦 — 직접 만든 보드·한 구간짜리·기존 보드
    GENERATING("generating"), // 뒷부분을 만드는 중
    FAILED("failed"); // 재시도까지 실패. POST /plan-generation/{planBoardId}/retry 로 이어서 다시 만든다

    companion object {
        fun of(value: String): PlanGenerationStatus = entries.firstOrNull { it.value == value } ?: COMPLETED
    }
}
