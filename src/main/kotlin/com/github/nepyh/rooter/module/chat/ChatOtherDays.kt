package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.AiChatBusyDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** 한 번에 받는 다른 날 일정의 최대 날짜 수. "매주 수요일" 이어도 플랜보드 기간 안 한 달 정도면 충분하다 */
internal const val MAX_OTHER_BUSY_DAYS = 31

/** 다른 날에 생긴 공부 불가능 시간 (#334). 시각은 0시부터의 분 */
internal data class OtherBusyDay(val date: LocalDate, val windows: List<Pair<Int, Int>>)

/** AI 가 준 다른 날 일정 중 반영할 날([days])과, 플랜보드 기간 밖·지난 날이라 반영하지 않을 날([outOfRange]) */
internal data class OtherBusyDays(val days: List<OtherBusyDay>, val outOfRange: List<LocalDate>)

/**
 * AI 가 준 other_busy_days 를 검증한다. 날짜·시각 형식이 틀리거나 시작이 끝보다 늦으면 버리고,
 * 대화한 날([planDate])은 plan_update 로 따로 처리하므로 뺀다. 같은 날 여러 시간대는 한 날로 묶는다.
 */
internal fun otherBusyDaysOf(
    raw: List<AiChatBusyDay>,
    planDate: LocalDate,
    today: LocalDate,
    boardStart: LocalDate,
    boardEnd: LocalDate
): OtherBusyDays {
    val parsed = raw.mapNotNull { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
        val start = aiClockOf(day.start) ?: return@mapNotNull null
        val end = aiClockOf(day.end) ?: return@mapNotNull null
        if (!start.isBefore(end) || date == planDate) return@mapNotNull null
        date to (start.toSecondOfDay() / 60 to end.toSecondOfDay() / 60)
    }
    val byDate = parsed.groupBy({ it.first }, { it.second }).toSortedMap()
    val (inRange, outOfRange) = byDate.keys.partition { !it.isBefore(today) && !it.isBefore(boardStart) && !it.isAfter(boardEnd) }
    return OtherBusyDays(
        days = inRange.take(MAX_OTHER_BUSY_DAYS).map { OtherBusyDay(it, byDate.getValue(it).distinct()) },
        outOfRange = outOfRange
    )
}

/** AI 가 준 시각("HH:mm"). "8시 이후 못 해" 를 끝 24:00 으로 주는 경우가 많아 하루 끝으로 받는다. 형식이 틀리면 null */
internal fun aiClockOf(text: String?): LocalTime? {
    val trimmed = text?.trim() ?: return null
    if (trimmed == "24:00") return LocalTime.MAX
    return runCatching { LocalTime.parse(trimmed) }.getOrNull()
}

/** 다른 날 재배치 결과. [moved] 는 시각이 바뀐 할일, [leftInPlace] 는 빈 시간이 없어 그대로 둔 할일 이름 */
internal data class OtherDayReschedule(val moved: List<RescheduledTask>, val leftInPlace: List<String>)

/**
 * 다른 날 계획에서 새 일정([windows])과 겹치는 할일만 그 할일의 원래 시각 이후 빈 시간으로 옮긴다.
 * 그날은 AI 가 할일 목록을 보지 않으므로 이름·길이는 그대로 두고 시각만 바꾼다 — 대화한 날처럼 AI 가 줄이거나 빼지 않는다.
 * AI 가 만든 할일은 겹쳐도 옮기지 않는다 (#245). 옮길 할일이 없으면 null.
 * 빈 시간이 없어 못 옮긴 할일은 지우지 않고 제자리에 둔다 (대화한 날이 아니라서 학생이 모르게 사라지면 안 됨).
 *
 * @param blocked 그날 수면·등교·불가능 시간 + 완료·실패 할일 + 다른 플랜보드 할일 (분)
 */
internal fun rescheduleOtherDay(
    pending: List<ExistingPendingTask>,
    windows: List<Pair<Int, Int>>,
    blocked: List<Pair<Int, Int>>,
    earliestMinute: Int
): OtherDayReschedule? {
    // 겹치는 할일만 옮기고, 안 겹치는 할일·AI 가 만든 할일은 제자리에 두고 그 시간대를 막는다
    val (overlapping, staying) = pending.partition { task ->
        !task.aiGenerated && windows.any { task.startMinutes < it.second && it.first < task.endMinutes }
    }
    if (overlapping.isEmpty()) return null

    val rescheduled = rescheduleKeepingUnaffected(
        existing = overlapping,
        aiTasks = overlapping.map { it.taskName to (it.endMinutes - it.startMinutes) },
        busyRanges = blocked + windows + staying.map { it.startMinutes to it.endMinutes },
        earliestMinute = earliestMinute
    )
    val placedIds = rescheduled.mapNotNull { it.existingId }.toSet()
    return OtherDayReschedule(rescheduled, overlapping.filter { it.id !in placedIds }.map { it.taskName })
}

/**
 * 다른 날 일정을 반영한 결과 답변. AI 문장 대신 실제로 옮긴 결과로 만든다 (옮기지 않았는데 옮겼다고 말하지 않도록).
 * @param results 반영할 날마다의 결과. null 이면 그날 계획이 없거나 겹치는 할일이 없어 그대로인 날
 */
internal fun otherDaysReply(
    busy: OtherBusyDays,
    results: Map<LocalDate, OtherDayReschedule?>,
    boardStart: LocalDate,
    boardEnd: LocalDate
): String = buildList {
    busy.days.forEach { day ->
        val result = results[day.date] ?: return@forEach
        if (result.moved.isNotEmpty()) {
            add(
                "${formatDay(day.date)} ${day.windows.joinToString(", ") { formatWindow(it) }} 일정을 피해서 " +
                    "${result.moved.joinToString(", ") { "'${it.taskName}'" }} 할일을 " +
                    "${result.moved.joinToString(", ") { "${it.startTime}~${it.endTime}" }}로 옮겼어요."
            )
        }
        if (result.leftInPlace.isNotEmpty()) {
            add("${formatDay(day.date)} ${result.leftInPlace.joinToString(", ") { "'$it'" }}은(는) 그날 빈 시간이 없어서 그대로 뒀어요.")
        }
    }
    val unchanged = busy.days.filter { results[it.date] == null }
    if (unchanged.isNotEmpty()) {
        add("${unchanged.joinToString(", ") { formatDay(it.date) }}에는 그 시간에 잡힌 할일이 없어서 그대로예요.")
    }
    if (busy.outOfRange.isNotEmpty()) {
        add(
            "${busy.outOfRange.joinToString(", ") { formatDay(it) }}은(는) 지난 날이거나 " +
                "플랜보드 기간(${formatDay(boardStart)}~${formatDay(boardEnd)}) 밖이라 바꾸지 않았어요."
        )
    }
    // 같은 요일이 두 번 이상이면 매주 반복되는 일정으로 보고, 앞으로 만드는 계획에도 반영되도록 등록을 권한다
    val dates = busy.days.map { it.date } + busy.outOfRange
    if (dates.groupingBy { it.dayOfWeek }.eachCount().values.any { it > 1 }) add(RECURRING_HINT)
}.joinToString(" ")

internal const val RECURRING_HINT = "매주 반복되는 일정이면 설정의 공부 불가능 시간에 등록해 두세요. 앞으로 만드는 계획에도 반영돼요."

internal fun formatDay(date: LocalDate): String =
    "${date.monthValue}월 ${date.dayOfMonth}일(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

private fun formatWindow(window: Pair<Int, Int>): String =
    "%02d:%02d~%02d:%02d".format(window.first / 60, window.first % 60, window.second / 60, window.second % 60)
