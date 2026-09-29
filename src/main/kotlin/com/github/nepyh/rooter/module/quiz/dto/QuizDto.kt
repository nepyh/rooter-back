package com.github.nepyh.rooter.module.quiz.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuizGenerateRequest(
    val date: String? = null // yyyy-MM-dd, 생략 시 오늘
)

@Serializable
data class QuizChoiceResponse(
    val id: Int,
    val choiceText: String
    // isCorrect 는 제출 전까지 노출하지 않음
)

@Serializable
data class QuizQuestionResponse(
    val id: Int,
    val questionText: String,
    val choices: List<QuizChoiceResponse>
)

@Serializable
data class QuizResponse(
    val dailyPlanId: Int,
    val quizDate: String,
    val questions: List<QuizQuestionResponse>
)

@Serializable
data class QuizAnswerSubmission(
    val questionId: Int,
    val selectedChoiceId: Int
)

@Serializable
data class QuizSubmitRequest(
    val answers: List<QuizAnswerSubmission>
)

@Serializable
data class WeakAreaSummary(
    val chapterName: String,
    val reviewTaskDescription: String
)

@Serializable
data class InsertedReviewTaskResponse(
    val dailyPlanId: Int,
    val planDate: String,
    val taskName: String,
    val startTime: String,        // "16:30"
    val endTime: String
)

@Serializable
data class QuizResultResponse(
    val totalQuestions: Int,
    val correctCount: Int,
    val weakAreas: List<WeakAreaSummary>,
    val insertedReviewTasks: List<InsertedReviewTaskResponse>,
    val results: List<QuizQuestionResult> = emptyList() // 제출한 문항별 채점 결과와 풀이
)

/** 제출한 문항 하나의 채점 결과. 앱은 isCorrect=false 인 문항에 풀이(explanation)를 보여주면 된다 */
@Serializable
data class QuizQuestionResult(
    val questionId: Int,
    val questionText: String,
    val selectedChoiceId: Int,
    val correctChoiceId: Int?,
    val correctChoiceText: String?,
    val isCorrect: Boolean,
    val explanation: String? // 풀이 과정. 풀이 컬럼 추가 전에 만든 퀴즈는 null
)
