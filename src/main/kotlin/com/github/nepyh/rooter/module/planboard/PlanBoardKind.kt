package com.github.nepyh.rooter.module.planboard

import java.time.LocalDate

/**
 * 직접 만든 일정 보드인지 (할일 응답의 isManual).
 * 사용자가 직접 추가하는 할일은 프론트가 시험일 없는 "기본 플랜보드"(POST /plan-boards)에 넣고,
 * AI 계획(POST /plan-generation)은 항상 시험일을 보내 만든다. 그래서 시험일이 없으면 직접 만든 보드로 본다
 * (rooter-front getBoardInfo 의 `board.examDate === null` 판정과 같은 규칙).
 */
fun isManualPlanBoard(examDate: LocalDate?): Boolean = examDate == null
