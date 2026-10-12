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

/** 퀴즈 문항 단원 이름의 최대 길이 (rooter-ddl chapters.chapter_name 과 같은 varchar(150)) */
const val QUIZ_CHAPTER_NAME_MAX = 150

/**
 * AI 가 문항에 붙인 단원 이름을 학습 범위 단원 중 하나로 맞춘다. 범위에 없는 이름이면 null(단원 미상).
 * 단원별 오답률 리포트가 이 이름으로 묶기 때문에, AI 가 이름을 조금이라도 바꿔 쓰면 받지 않는다.
 */
fun matchQuizChapter(raw: String?, chapterNames: Collection<String>): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it in chapterNames }
