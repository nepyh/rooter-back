package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.format.TextStyle
import java.util.Locale

/*
 * 챗봇이 플랜보드 기간 전체를 보고 고치기 위한 날짜별 계획 (#337).
 * 예전엔 AI 가 대화한 날(TARGET_DATE)의 할일만 봐서 "목요일 수학 줄여줘", "이번 주 너무 많아" 같은 요청을 못 했다.
 * 지난 날은 참고로만 보여주고, 오늘부터 플랜보드 끝까지만 고칠 수 있다 (지난 날을 바꾸면 완료 기록이 꼬인다).
 */

/** 프롬프트 <PLAN_DAYS> 에 싣는 날짜 수 상한. 플랜보드가 이보다 길면 대화한 날에서 가까운 날만 싣는다 */
internal const val MAX_PROMPT_PLAN_DAYS = 62

/** 그날 같은 플랜보드의 할일 하나 (완료·실패 포함) */
internal data class PlanDayTask(
    val id: Int,
    val name: String,
    val start: Int,
    val end: Int,
    val minutes: Int,
    val subjectId: Int?,
    val subjectName: String?,
    val status: PlanTaskStatus,
    val aiGenerated: Boolean,
    val postponedFromDate: LocalDate?
) {
    val pending get() = status == PlanTaskStatus.PENDING
}

/**
 * 플랜보드의 하루.
 * @param dailyPlanId 그날 계획이 아직 없으면 null (할일을 추가하거나 미뤄 오면 만든다)
 * @param otherBoardRanges 그날 다른 플랜보드의 할일 시간 (분) — 고칠 때 겹치지 않게 피한다
 */
internal data class PlanDay(
    val date: LocalDate,
    val dailyPlanId: Int?,
    val tasks: List<PlanDayTask>,
    val otherBoardRanges: List<Pair<Int, Int>>
) {
    val pendingTasks get() = tasks.filter { it.pending }
    val doneRanges get() = tasks.filter { !it.pending }.map { it.start to it.end }
}

/** 챗봇이 고칠 수 있는 날인지 — 오늘부터 플랜보드 끝까지 */
internal fun isEditableDay(date: LocalDate, today: LocalDate, boardEnd: LocalDate): Boolean =
    !date.isBefore(today) && !date.isAfter(boardEnd)

/**
 * 프롬프트 <PLAN_DAYS> 문자열. 날짜마다 한 줄이고, 고칠 수 있는 날인지와 할일(시각·분·과목·상태)을 적는다.
 * 기간이 [MAX_PROMPT_PLAN_DAYS] 보다 길면 대화한 날([target])에서 가까운 날부터 싣는다.
 */
internal fun planDaysText(days: List<PlanDay>, target: LocalDate, today: LocalDate, boardEnd: LocalDate): String {
    val shown = if (days.size <= MAX_PROMPT_PLAN_DAYS) {
        days
    } else {
        days.sortedBy { kotlin.math.abs(it.date.toEpochDay() - target.toEpochDay()) }.take(MAX_PROMPT_PLAN_DAYS).sortedBy { it.date }
    }
    return shown.joinToString("\n") { day ->
        val tags = buildList {
            if (day.date == target) add("대화 중인 날")
            if (day.date == today) add("오늘")
            add(if (isEditableDay(day.date, today, boardEnd)) "수정 가능" else "지난 날·수정 불가")
        }
        val tasks = day.tasks.sortedBy { it.start }.joinToString(" | ") { task ->
            val subject = task.subjectName?.let { ", $it" }.orEmpty()
            val noPostpone = if (task.pending && postponedFromPreviousDay(task.postponedFromDate, day.date)) ", 다시 못 미룸" else ""
            "${hhmm(task.start)}~${hhmm(task.end)} ${task.name} (${task.minutes}분$subject, ${statusLabel(task.status)}$noPostpone)"
        }.ifBlank { "할일 없음" }
        "- ${day.date} (${day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}) [${tags.joinToString(", ")}]: $tasks"
    }
}

/**
 * 프롬프트 <TODAY>. 날짜·요일에 이번 주·다음 주(월~일) 날짜 범위를 붙인다 —
 * AI 가 "다음 주"를 플랜보드가 걸친 이번 주로 계산하는 일이 잦아서 서버가 직접 계산해 준다.
 */
internal fun todayText(today: LocalDate): String {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun range(start: LocalDate) = "${dayWithWeekday(start)} ~ ${dayWithWeekday(start.plusDays(6))}"
    return "$today (${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)})\n" +
        "이번 주: ${range(monday)}\n" +
        "다음 주: ${range(monday.plusWeeks(1))}"
}

private fun dayWithWeekday(date: LocalDate) = "$date(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

private fun statusLabel(status: PlanTaskStatus): String = when (status) {
    PlanTaskStatus.PENDING -> "대기"
    PlanTaskStatus.DONE -> "완료"
    else -> "실패"
}

/** 편집 대상 할일 목록 — 그날 대기 할일에 퀴즈 상태를 붙인다 ([quizState]: 할일 id → 결과 확정 여부, 키가 없으면 퀴즈 안 함) */
internal fun PlanDay.editableTasks(quizState: Map<Int, Boolean>): List<EditableTask> = pendingTasks.map {
    val state = quizState[it.id]
    EditableTask(
        id = it.id,
        name = it.name,
        start = it.start,
        end = it.end,
        subjectId = it.subjectId,
        quizStarted = state != null,
        quizFinalized = state == true,
        aiGenerated = it.aiGenerated
    )
}

internal fun PlanDayTask.toExisting() = ExistingPendingTask(id, name, start, end, aiGenerated = aiGenerated)
