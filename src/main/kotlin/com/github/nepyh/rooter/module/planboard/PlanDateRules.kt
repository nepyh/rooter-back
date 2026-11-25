package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 계획 기간(시작일~종료일, 양 끝 포함) 최대 일수 (#401) */
internal const val MAX_PLAN_DAYS = 60

/** 시험일·계획 종료일은 오늘부터 이 기간 안이어야 한다 (#401) */
internal const val MAX_AHEAD_YEARS = 1L

/*
 * 계획 날짜 범위 규칙 (#401, 11-25 결정). 어기면 모두 400 INVALID_DATE_RANGE, 사유는 message 로 나눈다.
 * - 시작일 ≥ 오늘 (APP_ZONE)
 * - 시험일 ≤ 오늘 + 1년
 * - 계획 기간 ≤ 60일, 종료일 ≤ 오늘 + 1년
 * 예전엔 상한이 없어 과거 날짜로 계획이 만들어지고, 2100년까지 늘린 보드는 요청 하나가 NICE 를 학기마다(약 150번) 불러
 * 할일 수정·옮기기가 타임아웃에 걸렸다. 수정은 바뀐 값만 검사한다 — 이미 시작한 보드의 지난 시작일은 그대로 둘 수 있게.
 */

internal fun requireStartNotPast(startDate: LocalDate, today: LocalDate) {
    if (startDate.isBefore(today)) {
        throw PlanBoardValidationException.InvalidDateRangeException("시작일은 오늘 이후여야 합니다.")
    }
}

internal fun requirePlanLengthWithinLimit(startDate: LocalDate, endDate: LocalDate, today: LocalDate) {
    if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_PLAN_DAYS) {
        throw PlanBoardValidationException.InvalidDateRangeException("계획 기간은 최대 ${MAX_PLAN_DAYS}일입니다.")
    }
    if (endDate.isAfter(today.plusYears(MAX_AHEAD_YEARS))) {
        throw PlanBoardValidationException.InvalidDateRangeException("계획은 오늘부터 1년 안의 날짜로만 만들 수 있습니다.")
    }
}

internal fun requireExamWithinLimit(examDate: LocalDate?, today: LocalDate) {
    if (examDate != null && examDate.isAfter(today.plusYears(MAX_AHEAD_YEARS))) {
        throw PlanBoardValidationException.InvalidDateRangeException("시험일은 오늘부터 1년 안이어야 합니다.")
    }
}
