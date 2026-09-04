package com.github.nepyh.rooter.module.leveltest.dto

import kotlinx.serialization.Serializable

@Serializable
data class LevelTestGenerateRequest(
    val grade: Int // 중학교 학년(1~3). 실제 출제는 한 학년 아래 수준으로 나간다.
)

@Serializable
data class LevelTestQuestionPublicResponse(
    val id: Int,
    val subject: String,
    val questionText: String,
    val choices: List<String>
)

@Serializable
data class LevelTestGenerateResponse(
    val attemptId: Int,
    val referenceGradeLabel: String,
    val questions: List<LevelTestQuestionPublicResponse>
)

@Serializable
data class LevelTestAnswer(
    val questionId: Int,
    val selectedIndex: Int
)

@Serializable
data class LevelTestSubmitRequest(
    val answers: List<LevelTestAnswer>
)

@Serializable
data class LevelTestQuestionResultResponse(
    val questionId: Int,
    val subject: String,
    val isCorrect: Boolean,
    val selectedIndex: Int,
    val correctIndex: Int,
    val explanation: String
)

@Serializable
data class LevelTestSubjectScoreResponse(
    val subject: String,
    val correctCount: Int,
    val totalCount: Int,
    val tier: String // "상" | "중" | "하"
)

@Serializable
data class LevelTestSubmitResponse(
    val correctCount: Int,
    val totalCount: Int,
    val tier: String, // "상" | "중" | "하" (전체 통합 등급)
    val subjectScores: List<LevelTestSubjectScoreResponse>,
    val results: List<LevelTestQuestionResultResponse>
)
