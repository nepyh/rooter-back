package com.github.nepyh.rooter.module.taskquiz.dto

import kotlinx.serialization.Serializable

@Serializable
data class TaskQuizChoiceResponse(
    val id: Int,
    val choiceText: String
)

@Serializable
data class TaskQuizQuestionResponse(
    val id: Int,
    val questionText: String,
    val choices: List<TaskQuizChoiceResponse>
)

@Serializable
data class TaskQuizResponse(
    val planTaskId: Int,
    val attemptNumber: Int,
    val questions: List<TaskQuizQuestionResponse>
)

@Serializable
data class TaskQuizAnswerRequest(
    val selectedChoiceId: Int
)

/** 문제 하나를 풀 때마다 즉시 돌려주는 채점 결과. 한 번 답하면 같은 문제엔 다시 호출할 수 없음 */
@Serializable
data class TaskQuizAnswerResponse(
    val questionId: Int,
    val isCorrect: Boolean,
    val correctChoiceId: Int,
    val explanation: String
)

/** 제출 시점에 DB 에 저장된 답으로 채점한 문항별 결과 */
@Serializable
data class TaskQuizQuestionResult(
    val questionId: Int,
    val questionText: String,
    val selectedChoiceId: Int,
    val correctChoiceId: Int,
    val correctChoiceText: String,
    val isCorrect: Boolean,
    val explanation: String
)

@Serializable
data class TaskQuizSubmitResponse(
    val attemptNumber: Int,
    val correctCount: Int,
    val totalCount: Int,
    // 퀴즈 자체가 완료 확인 수단이라, passed 가 true 면 이 응답과 함께 태스크가 자동으로 완료 처리됨
    val passed: Boolean,
    // true 면 10분 뒤 재시도 퀴즈가 자동으로 생성됨. attemptNumber 3까지 실패하면 재시도는 더 없고
    // 대신 taskInvalidated 가 true 로 내려가며 해당 태스크가 미완료로 확정됨
    val retryScheduled: Boolean,
    val taskInvalidated: Boolean,
    val results: List<TaskQuizQuestionResult>
)
