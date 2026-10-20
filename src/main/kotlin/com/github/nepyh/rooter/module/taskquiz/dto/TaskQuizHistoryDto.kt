package com.github.nepyh.rooter.module.taskquiz.dto

import kotlinx.serialization.Serializable

/**
 * 퀴즈 히스토리 항목 하나 = 퀴즈 1차수.
 * 일일 퀴즈도 사양이 확정되면 같은 형태로 합류할 예정이라 type 으로 구분한다.
 */
@Serializable
data class QuizHistoryItemResponse(
    val quizId: Int,            // 차수 id (task_quiz_attempts.id). 상세 조회에 그대로 쓴다
    val type: String,           // TASK (일일 퀴즈는 추후 DAILY)
    val status: String,         // TODO(오늘 풀 퀴즈) / MISSED(계획 날짜가 지나도록 안 푼 퀴즈, 지금 풀어도 됨) / DONE(응시 완료)
    val planTaskId: Int,
    val attemptNumber: Int,     // 차수 (최초 1, 재시도 2~3)
    val title: String,          // 할일 이름 (plan_tasks.task_name)
    val subjectId: Int?,        // 과목. 문항 단원으로도 할일 이름으로도 못 찾으면 null
    val subjectName: String?,
    val createdAt: String,      // 퀴즈 생성 시간 (오프셋 포함)
    val scheduledAt: String?,   // 응시 예정 시간 = 할일이 끝나는 시각(퀴즈가 열리는 시각). 날짜만 아는 퀴즈는 날짜만
    val completedAt: String?,   // 완료(제출) 시간. null = 미응시 또는 컬럼 추가 전에 푼 퀴즈
    val correctCount: Int?,     // null = 미응시
    val totalCount: Int,
    val passed: Boolean?        // null = 미응시
)

@Serializable
data class QuizHistoryListResponse(
    val quizzes: List<QuizHistoryItemResponse>, // 최신순
    val totalCount: Int,                        // 같은 status 조건의 전체 수
    val nextCursor: Int?                        // 다음 페이지에 넘길 cursor. null 이면 마지막 페이지
)
