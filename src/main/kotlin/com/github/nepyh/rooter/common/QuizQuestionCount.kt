package com.github.nepyh.rooter.common

/**
 * 공부한 시간(분)에 맞춘 퀴즈 문항 수 — 30분 이하 4문항, 1시간 이하 5문항, 1시간 초과 7문항.
 * 태스크 퀴즈는 그 할일의 estimatedMinutes, 일일 퀴즈는 그날 완료한 할일들의 estimatedMinutes 합을 넣는다.
 */
fun quizQuestionCount(studyMinutes: Int): Int = when {
    studyMinutes <= 30 -> 4
    studyMinutes <= 60 -> 5
    else -> 7
}
