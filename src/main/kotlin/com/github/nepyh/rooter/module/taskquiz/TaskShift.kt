package com.github.nepyh.rooter.module.taskquiz

import java.time.LocalTime

/** 태스크 완료 퀴즈에서 떨어져 재시도가 잡힐 때마다 남은 계획을 미는 시간 (재시도 대기 10분 + 퀴즈 푸는 시간 약 5분) */
const val QUIZ_FAIL_SHIFT_MINUTES = 15

private const val LAST_MINUTE_OF_DAY = 24 * 60 - 1 // 23:59

/** 밀 대상 태스크 (시각은 0시부터의 분) */
data class ShiftableTask(val id: Int, val startMinutes: Int, val endMinutes: Int)

data class ShiftedTask(val id: Int, val startTime: LocalTime, val endTime: LocalTime)

/**
 * 태스크들을 [shiftMinutes] 만큼 뒤로 민다.
 *
 * - 순서와 길이는 유지하고, 앞 태스크와 겹치지 않게 이어 붙인다 (원래 간격이 있으면 그대로 둔다).
 * - [blocked](학원 같은 공부 불가능 시간, 밀지 않는 다른 태스크)와 겹치면 그 뒤로 넘긴다.
 * - 밀려서 23시(취침)를 넘어도 그날 안에 넣는다. 자정을 넘길 수는 없어서 끝이 23:59 를 넘으면 23:59 에 맞춘다.
 */
fun shiftLaterTasks(tasks: List<ShiftableTask>, shiftMinutes: Int, blocked: List<Pair<Int, Int>>): List<ShiftedTask> {
    var cursor = 0
    return tasks.sortedBy { it.startMinutes }.map { task ->
        val duration = task.endMinutes - task.startMinutes
        var start = maxOf(task.startMinutes + shiftMinutes, cursor)
        while (true) {
            val overlap = blocked.firstOrNull { start < it.second && it.first < start + duration } ?: break
            start = overlap.second
        }
        var end = start + duration
        if (end > LAST_MINUTE_OF_DAY) {
            end = LAST_MINUTE_OF_DAY
            start = minOf(start, end - 1)
        }
        cursor = end
        ShiftedTask(task.id, toTime(start), toTime(end))
    }
}

private fun toTime(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)
