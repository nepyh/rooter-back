package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import java.time.LocalTime

private const val DAY_MINUTES = 24 * 60

/** 재조정 전 그날의 미완료 태스크 (시각은 0시부터의 분) */
data class ExistingPendingTask(val id: Int, val taskName: String, val startMinutes: Int, val endMinutes: Int)

/** 재조정 결과. existingId 가 있으면 그 태스크를 제자리 수정, 없으면 새로 만든다 */
data class RescheduledTask(
    val existingId: Int?,
    val taskName: String,
    val estimatedMinutes: Int,
    val startTime: LocalTime,
    val endTime: LocalTime
)

/**
 * 챗봇 재조정 결과(AI 가 준 태스크 이름·소요시간 목록)를 시간표에 배치한다.
 *
 * - AI 목록의 태스크를 이름으로 기존 태스크와 짝짓고, 원래 시작 시각에 (새 소요시간으로) 그대로 들어가면 **제자리에 둔다.**
 *   → 새 일정과 안 겹치는 태스크는 시각이 바뀌지 않는다 (예전엔 전부 그날 가장 이른 빈 시간부터 다시 깔았음).
 * - 제자리에 못 두는 태스크(새 일정과 겹치거나, 새로 생긴 태스크)만 빈 시간으로 옮긴다.
 *   [earliestMinute] 이전, 그리고 옮기는 태스크의 원래 시각 이전으로는 앞당기지 않는다.
 *   → 오늘 계획이면 이미 지난 시간에 들어가지 않고, 다른 날이어도 저녁 태스크가 아침으로 튀지 않는다.
 * - 빈 시간이 모자라 못 넣은 태스크는 결과에서 빠진다 (기존 동작과 같음).
 *
 * @param busyRanges 학교·수면·불가능 시간 + 새로 생긴 공부 불가능 시간 + 완료 태스크 시간대 (분)
 * @param earliestMinute 옮기는 태스크를 이 시각(분) 이후에만 넣는다. 오늘이면 현재 시각, 다른 날이면 0
 */
fun rescheduleKeepingUnaffected(
    existing: List<ExistingPendingTask>,
    aiTasks: List<Pair<String, Int>>,
    busyRanges: List<Pair<Int, Int>>,
    earliestMinute: Int
): List<RescheduledTask> {
    val unmatched = existing.toMutableList()
    val kept = mutableListOf<RescheduledTask>()
    val keptRanges = mutableListOf<Pair<Int, Int>>()
    val toMove = mutableListOf<Pair<Pair<String, Int>, Int?>>() // (이름, 소요시간) to 기존 id

    for ((name, minutes) in aiTasks) {
        val original = unmatched.firstOrNull { it.taskName == name }?.also { unmatched.remove(it) }
        if (original != null) {
            val start = original.startMinutes
            val end = start + minutes
            val fitsInPlace = end < DAY_MINUTES && (busyRanges + keptRanges).none { start < it.second && it.first < end }
            if (fitsInPlace) {
                kept += RescheduledTask(original.id, name, minutes, toTime(start), toTime(end))
                keptRanges += start to end
                continue
            }
        }
        toMove += (name to minutes) to original?.id
    }

    // 옮기는 태스크는 원래 시각보다 앞당기지 않는다 (17~19시 학원이면 아침이 아니라 19시 이후로).
    // 새로 생긴 태스크만 옮길 때는 그날 기존 태스크 중 가장 이른 시각을 기준으로 한다.
    val anchor = toMove.mapNotNull { (_, id) -> existing.firstOrNull { it.id == id }?.startMinutes }.minOrNull()
        ?: existing.minOfOrNull { it.startMinutes }
        ?: 0
    val notBefore = maxOf(earliestMinute, anchor)
    val blocked = busyRanges + keptRanges + if (notBefore > 0) listOf(0 to notBefore) else emptyList()
    val placed = PlanTaskScheduler.placeTasks(toMove.map { it.first }, PlanTaskScheduler.freeIntervalsFromBusyRanges(blocked))
    // placeTasks 는 순서를 지키고, 자리가 없으면 거기서 멈추므로 앞에서부터 짝이 맞는다
    val moved = placed.mapIndexed { i, task ->
        RescheduledTask(toMove[i].second, task.taskName, task.estimatedMinutes, task.startTime, task.endTime)
    }

    return (kept + moved).sortedBy { it.startTime }
}

private fun toTime(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)
