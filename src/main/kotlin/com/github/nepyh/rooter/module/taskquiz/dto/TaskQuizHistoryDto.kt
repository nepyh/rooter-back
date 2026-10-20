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

/** 상세 화면의 보기. 제출이 끝난 차수만 정답 여부·틀린 이유를 채우고, 미응시 차수는 null 로 숨긴다 */
@Serializable
data class QuizHistoryChoiceResponse(
    val id: Int,
    val choiceText: String,
    val selected: Boolean,      // 내가 고른 보기
    val isCorrect: Boolean?,    // 미응시 차수는 null (정답 노출 금지)
    val reason: String?         // 이 보기가 왜 틀렸는지 (답변 API 의 reason 과 같은 뜻). 정답 보기·미응시 차수는 null
)

/** 상세 화면의 문항 하나 */
@Serializable
data class QuizHistoryQuestionResponse(
    val questionId: Int,
    val questionText: String,
    val chapterName: String?,   // 단원 미상이면 null
    val selectedChoiceId: Int?, // 아직 안 고른 문제는 null
    val isCorrect: Boolean?,    // 미응시 차수는 null
    val explanation: String?,   // 문항 풀이(정답 보기의 풀이). 미응시 차수는 null
    val choices: List<QuizHistoryChoiceResponse>
)

/** 퀴즈 히스토리 상세 = 차수 하나. 목록 항목 메타에 통과 기준과 문항을 더한 형태 */
@Serializable
data class QuizHistoryDetailResponse(
    val quizId: Int,
    val type: String,
    val status: String,
    val planTaskId: Int,
    val attemptNumber: Int,
    val title: String,
    val subjectId: Int?,
    val subjectName: String?,
    val createdAt: String,
    val scheduledAt: String?,
    val completedAt: String?,
    val correctCount: Int?,
    val totalCount: Int,
    val passCount: Int,          // 통과에 필요한 정답 수 (70점 이상)
    val passed: Boolean?,
    val questions: List<QuizHistoryQuestionResponse>
)
