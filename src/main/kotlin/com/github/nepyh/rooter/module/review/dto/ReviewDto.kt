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
    val chapterName: String?, // 문제의 단원. 맞춤 문제는 약점 단원, 오답 복사분은 원본 문항의 단원 (단원 미상이면 null)
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

/** 단원 하나의 퀴즈 오답률 */
@Serializable
data class ChapterWrongRateResponse(
    val chapterName: String,
    val answeredCount: Int, // 일일·태스크 퀴즈에서 이 단원 문제를 푼 수
    val wrongCount: Int, // 그중 틀린 수
    val wrongRate: Int, // 오답률 % (wrongCount ÷ answeredCount, 반올림 0~100)
    val openReviewCount: Int // 이 단원에서 아직 맞히지 않은 복습 문제 수 (오답 복사분 + 맞춤 문제)
)

@Serializable
data class ReviewReportResponse(
    val chapters: List<ChapterWrongRateResponse>, // 오답률 높은 단원부터 (같으면 틀린 수 많은 순)
    val untaggedAnsweredCount: Int // 단원을 알 수 없어 위 집계에서 빠진 푼 문제 수 (단원 기록 전에 푼 퀴즈 등)
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
