package com.github.nepyh.rooter.module.review.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReviewChoiceResponse(
    val id: Int,
    val choiceText: String
    // 정답 여부는 답하기 전까지 노출하지 않음
)

@Serializable
data class ReviewQuestionResponse(
    val id: Int,
    val source: String, // DAILY_QUIZ(일일 퀴즈 오답) / TASK_QUIZ(태스크 퀴즈 오답) / CUSTOM(약점 맞춤 문제)
    val chapterName: String?, // 맞춤 문제의 약점 단원. 오답 복사분은 null
    val questionText: String,
    val choices: List<ReviewChoiceResponse>,
    val wrongCount: Int, // 복습에서 틀린 횟수
    val createdAt: String
)

@Serializable
data class ReviewQuestionListResponse(
    val questions: List<ReviewQuestionResponse>, // 최근에 들어온 문제부터
    val totalCount: Int, // 아직 해결하지 않은 복습 문제 전체 수
    val nextCursor: Int? // 다음 페이지를 받을 때 cursor 로 넘길 값. null 이면 마지막 페이지
)

@Serializable
data class ReviewAnswerRequest(
    val selectedChoiceId: Int
)

@Serializable
data class ReviewAnswerResponse(
    val questionId: Int,
    val isCorrect: Boolean,
    val correctChoiceId: Int,
    val reason: String?, // 틀렸을 때 고른 보기가 왜 틀렸는지 (없으면 null). 맞으면 null
    val explanation: String?, // 이 문제의 자세한 풀이 (예전 일일 퀴즈 복사분은 null)
    val cleared: Boolean // true 면 복습 목록에서 빠짐 (맞혔을 때)
)
