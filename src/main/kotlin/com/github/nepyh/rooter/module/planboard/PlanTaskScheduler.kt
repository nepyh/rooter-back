package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import java.time.LocalDate
import java.time.LocalTime

private const val DAY_MINUTES = 24 * 60
private val DEFAULT_UNAVAILABLE_RANGES = listOf(0 to (6 * 60 + 30), (23 * 60) to DAY_MINUTES) // 00:00~06:30, 23:00~24:00
private val SCHOOL_PREP_RANGE = (7 * 60) to (8 * 60) // 07:00~08:00, 등교 준비(세면/식사/이동), 평일만
private const val SCHOOL_START_MINUTES = 8 * 60 + 30 // 08:30 등교, 고정
private val DEFAULT_SCHOOL_HOURS = SCHOOL_START_MINUTES to (16 * 60 + 30) // NICE 시간표를 못 가져올 때 쓰는 폴백값 (08:30~16:30)
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

    /**
     * 날짜별 학습 불가 시간대를 만든다 (plan-generation 의 여러 날짜 생성, chat 재조정의
     * 하루짜리 범위 둘 다 이 함수로 통일해서 씀 — 재조정은 startDate == endDate 로 호출).
     *
     * 사용자가 직접 등록한 시간대(customRows)가 있으면 그것만 쓰고(기존 동작 유지),
     * 없으면 취침시간 기본값 + 평일 학교시간을 채우는데, 학교시간은 NICE 실시간 시간표로
     * 그날의 마지막 교시를 조회해 하교시각을 계산한다 (실패/데이터없음 시 기존 기본값으로 폴백).
     */
    suspend fun buildUnavailableRanges(
        schoolDataFetcher: SchoolDataFetcher,
        startDate: LocalDate,
        endDate: LocalDate,
        schoolId: String?,
        classNumber: Int?,
        grade: Int,
        customRows: List<Pair<Int, Pair<Int, Int>>>
    ): Map<LocalDate, List<Pair<Int, Int>>> {
        val dates = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.toList()

        if (customRows.isNotEmpty()) {
            val byWeekday = customRows.groupBy({ it.first }, { it.second })
            return dates.associateWith { date -> byWeekday[date.dayOfWeek.value].orEmpty() }
        }

        val dismissalMinutesByDate = if (schoolId != null) {
            runCatching { fetchDismissalMinutesByDate(schoolDataFetcher, schoolId, classNumber, grade, startDate, endDate) }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }

        return dates.associateWith { date ->
            val ranges = DEFAULT_UNAVAILABLE_RANGES.toMutableList()
            if (date.dayOfWeek.value <= 5) { // 평일(월~금)만 등교 준비 + 학교시간 추가
                ranges.add(SCHOOL_PREP_RANGE)
                val schoolHours = dismissalMinutesByDate[date]?.let { SCHOOL_START_MINUTES to it } ?: DEFAULT_SCHOOL_HOURS
                ranges.add(schoolHours)
            }
            ranges
        }
    }

    /** NICE 시간표에서 날짜별 마지막 교시를 찾아 하교시각(분)으로 변환한다. 학기가 바뀌는 기간이면 학기별로 나눠 조회한다. */
    private suspend fun fetchDismissalMinutesByDate(
        schoolDataFetcher: SchoolDataFetcher,
        schoolId: String,
        classNumber: Int?,
        grade: Int,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<LocalDate, Int> {
        val className = classNumber?.toString()
        val semesters = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .map { academicYearAndSemester(it) }
            .distinct()

        val lastPeriodByDate = mutableMapOf<LocalDate, Int>()
        for ((year, semester) in semesters) {
            schoolDataFetcher.getTimetable(schoolId, year, semester, grade, className).forEach { entry ->
                if (entry.period > (lastPeriodByDate[entry.date] ?: 0)) {
                    lastPeriodByDate[entry.date] = entry.period
                }
            }
        }

        return lastPeriodByDate.mapValues { (_, lastPeriod) -> dismissalMinutesForLastPeriod(lastPeriod) }
    }

    /**
     * 하교 시각 = 09:10 + (그날 마지막 교시 수 × 60분).
     * "6교시면 15:10, 7교시면 16:10" 두 지점으로부터 도출한 선형식 — NICE 는 교시 번호만 주고
     * 실제 시각(등/하교 종 치는 시각)은 학교마다 달라서 공공데이터로 안 열려있기 때문에 근사치로 씀.
     */
    private fun dismissalMinutesForLastPeriod(lastPeriod: Int): Int = (9 * 60 + 10) + lastPeriod * 60

    /** 3~8월은 1학기, 9~2월은 2학기. 학년도(AY)는 학년도가 시작하는 연도(3월 기준) 기준. */
    private fun academicYearAndSemester(date: LocalDate): Pair<Int, Int> {
        val academicYear = if (date.monthValue >= 3) date.year else date.year - 1
        val semester = if (date.monthValue in 3..8) 1 else 2
        return academicYear to semester
    }

    fun toMinutes(time: LocalTime): Int = time.hour * 60 + time.minute

    /**
     * 이미 날짜별로 정확히 계산된 불가 시간 목록(예: [buildUnavailableRanges]가 NICE 시간표로
     * 계산한 그날의 하교시각 기반 범위)으로 그날의 빈 시간을 계산한다.
     */
    fun freeIntervalsFromBusyRanges(busyRanges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val busy = busyRanges.sortedBy { it.first }

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
