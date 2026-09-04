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
    val taskName: String
)

@Serializable
data class QuizResultResponse(
    val totalQuestions: Int,
    val correctCount: Int,
    val weakAreas: List<WeakAreaSummary>,
    val insertedReviewTasks: List<InsertedReviewTaskResponse>
)
