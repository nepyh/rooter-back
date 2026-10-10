package com.github.nepyh.rooter.module.taskquiz.dto

import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
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
    val choices: List<TaskQuizChoiceResponse>,
    // 이미 답한 문제면 고른 보기 id, 아직이면 null — 앱을 껐다 켜도 이어서 풀 수 있게
    val selectedChoiceId: Int? = null
)

@Serializable
data class TaskQuizSubjectResponse(
    val subjectId: Int,
    val subjectName: String
)

@Serializable
data class TaskQuizResponse(
    val planTaskId: Int,
    val attemptNumber: Int,
    val questions: List<TaskQuizQuestionResponse>, // 문항 수는 할일 길이에 따라 3·5·7개
    val passCount: Int = 0, // 통과에 필요한 정답 수 (문항의 80% 이상)
    // 이 태스크의 과목 (추정). 태스크 이름에 과목명이 하나만 있으면 그 과목, 아니면 플랜보드 과목이 하나뿐일 때 그 과목, 그 외엔 null
    val subject: TaskQuizSubjectResponse? = null,
    val subjects: List<TaskQuizSubjectResponse> = emptyList() // 플랜보드 학습 범위 과목 (퀴즈는 이 범위에서 출제)
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
    val reason: String?, // 틀렸을 때 고른 보기가 왜 틀렸는지 한두 문장. 맞으면 null
    val explanation: String // 이 문제의 자세한 풀이 (제출 응답 results[].explanation 과 같은 값)
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
    val passCount: Int = 0, // 통과에 필요한 정답 수 (문항의 80% 이상)
    // 퀴즈 자체가 완료 확인 수단이라, passed 가 true 면 이 응답과 함께 태스크가 자동으로 완료 처리됨
    val passed: Boolean,
    // true 면 10분 뒤 재시도 퀴즈가 자동으로 생성됨. attemptNumber 3까지 실패하면 재시도는 더 없고
    // 대신 taskInvalidated 가 true 로 내려가며 해당 태스크가 미완료로 확정됨
    val retryScheduled: Boolean,
    val taskInvalidated: Boolean,
    val results: List<TaskQuizQuestionResult>,
    // retryScheduled 일 때, 재시도 퀴즈를 풀 시간만큼 뒤로 민 오늘의 남은 태스크 (앱은 이 값이나 할 일 목록 새로고침으로 반영)
    val shiftedTasks: List<PlanTaskResponse> = emptyList()
)
