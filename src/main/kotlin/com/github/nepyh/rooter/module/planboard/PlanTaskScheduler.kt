package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate
import java.time.LocalTime

private const val DAY_MINUTES = 24 * 60
private val DEFAULT_UNAVAILABLE_RANGES = listOf(0 to (6 * 60 + 30), (23 * 60) to DAY_MINUTES) // 00:00~06:30, 23:00~24:00
private val DEFAULT_SCHOOL_HOURS = (8 * 60 + 30) to (16 * 60 + 30) // 08:30~16:30, 평일만
private const val DEFAULT_BREAK_MINUTES = 10

data class PlacedTask(
    val taskName: String,
    val estimatedMinutes: Int,
    val startTime: LocalTime,
    val endTime: LocalTime
)

/**
 * 하루치 태스크를 빈 시간대에 순서대로 채워 넣는 결정론적 스케줄러.
 * AI 는 태스크 이름/소요시간만 정하고, 실제 시각 배정은 항상 여기서 계산한다
 * (plan-generation, chat 기반 계획 재조정 둘 다 이 로직을 공유).
 */
object PlanTaskScheduler {

    fun loadUnavailableRanges(userId: Int): Map<Int, List<Pair<Int, Int>>> {
        val rows = UnavailableTimeTable.selectAll()
            .where { UnavailableTimeTable.user eq userId }
            .map { it[UnavailableTimeTable.dayOfWeek] to (toMinutes(it[UnavailableTimeTable.startTime]) to toMinutes(it[UnavailableTimeTable.endTime])) }

        if (rows.isEmpty()) {
            return DayOfWeek.entries.associate { day ->
                val ranges = DEFAULT_UNAVAILABLE_RANGES.toMutableList()
                if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) ranges.add(DEFAULT_SCHOOL_HOURS)
                day.code.toInt() to ranges
            }
        }

        return rows.groupBy({ it.first.code.toInt() }, { it.second })
    }

    fun toMinutes(time: LocalTime): Int = time.hour * 60 + time.minute

    /**
     * @param extraBusyRanges 그날 하루에만 추가로 적용할 임시 불가능 시간 (분 단위).
     *   챗봇이 대화로 새로 파악한 일정(예: "오늘 5~10시 조부모님댁") 등에 사용.
     */
    fun freeIntervalsForDate(
        date: LocalDate,
        unavailableByDay: Map<Int, List<Pair<Int, Int>>>,
        extraBusyRanges: List<Pair<Int, Int>> = emptyList()
    ): List<Pair<Int, Int>> {
        val dayOfWeek = date.dayOfWeek.value // 1=월 ... 7=일
        val busy = (unavailableByDay[dayOfWeek].orEmpty() + extraBusyRanges).sortedBy { it.first }

        val merged = mutableListOf<Pair<Int, Int>>()
        for ((start, end) in busy) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, end)
            } else {
                merged.add(start to end)
            }
        }

        val free = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        for ((start, end) in merged) {
            if (start > cursor) free.add(cursor to start)
            cursor = maxOf(cursor, end)
        }
        if (cursor < DAY_MINUTES) free.add(cursor to DAY_MINUTES)
        return free
    }

    fun placeTasks(items: List<Pair<String, Int>>, freeIntervals: List<Pair<Int, Int>>): List<PlacedTask> {
        if (items.isEmpty() || freeIntervals.isEmpty()) return emptyList()

        var intervalIndex = 0
        var cursor = freeIntervals.getOrNull(0)?.first ?: 0

        val result = mutableListOf<PlacedTask>()
        for ((taskName, minutes) in items) {
            while (intervalIndex < freeIntervals.size && cursor + minutes > freeIntervals[intervalIndex].second) {
                intervalIndex++
                cursor = freeIntervals.getOrNull(intervalIndex)?.first ?: cursor
            }
            if (intervalIndex >= freeIntervals.size) break // 남은 빈 시간이 없으면 이후 task는 배치하지 않음

            val start = cursor
            val end = start + minutes
            cursor = end + DEFAULT_BREAK_MINUTES
            result.add(
                PlacedTask(
                    taskName = taskName.take(150),
                    estimatedMinutes = minutes,
                    startTime = minutesToLocalTime(start),
                    endTime = minutesToLocalTime(end)
                )
            )
        }
        return result
    }

    private fun minutesToLocalTime(totalMinutes: Int): LocalTime {
        val clamped = totalMinutes.coerceIn(0, DAY_MINUTES - 1)
        return LocalTime.of(clamped / 60, clamped % 60)
    }
}
