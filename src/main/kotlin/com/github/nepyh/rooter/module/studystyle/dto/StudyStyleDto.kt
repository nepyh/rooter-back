package com.github.nepyh.rooter.module.studystyle.dto

import kotlinx.serialization.Serializable

@Serializable
data class StudyStyleAnswerInput(
    val questionNumber: Int, // 1~7
    val answerOption: Int // 1~3: 실제 보기, 4: "모르겠어요"
)

@Serializable
data class StudyStyleSubmitRequest(
    val answers: List<StudyStyleAnswerInput>
)

@Serializable
data class StudyStyleAnswerResponse(
    val questionNumber: Int,
    val answerOption: Int
)

@Serializable
data class StudyStyleResponse(
    val answers: List<StudyStyleAnswerResponse>
)
