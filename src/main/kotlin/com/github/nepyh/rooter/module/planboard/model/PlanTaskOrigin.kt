package com.github.nepyh.rooter.module.planboard.model

/**
 * 할일 생성 출처. DB(plan_tasks.origin)에는 이름 그대로 저장한다.
 * - USER: 사용자가 직접 추가한 할일(POST /plan-tasks)과 챗봇이 만든 할일. 삭제할 수 있다
 * - AI: 서버 AI 흐름이 만든 할일(시험 계획 생성·재조정 보충·퀴즈 복습). 삭제할 수 없다
 *
 * 챗봇은 유저와 같은 권한이라, 챗봇이 만든 할일은 USER 로 둔다 — "AI 가 만든 일정"과 챗봇은 다르다 (#245)
 */
enum class PlanTaskOrigin {
    USER, AI
}
