package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import java.time.LocalDate

/** "yyyy-MM-dd" 를 날짜로. 형식이 틀리면 INVALID_DATE_FORMAT */
internal fun parseBoardDate(raw: String): LocalDate =
    runCatching { LocalDate.parse(raw) }.getOrElse { throw PlanBoardValidationException.InvalidDateFormatException() }

/**
 * 시험 기간 검증 (#376). 시험 마지막 날은 시험 첫날이 있어야 하고 첫날보다 앞설 수 없다 — 어기면 INVALID_DATE_RANGE.
 * DDL 의 chk_plan_boards_exam_end_date 와 같은 규칙을 먼저 확인해 500 대신 400 으로 돌려준다.
 */
internal fun requireValidExamPeriod(examDate: LocalDate?, examEndDate: LocalDate?) {
    if (examEndDate == null) return
    if (examDate == null || examEndDate.isBefore(examDate)) throw PlanBoardValidationException.InvalidDateRangeException()
}
