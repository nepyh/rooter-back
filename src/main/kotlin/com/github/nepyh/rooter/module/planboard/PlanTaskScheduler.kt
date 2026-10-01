package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import java.time.LocalDate
import java.time.LocalTime

private const val DAY_MINUTES = 24 * 60
private val DEFAULT_UNAVAILABLE_RANGES = listOf(0 to (6 * 60 + 30), (23 * 60) to DAY_MINUTES) // 00:00~06:30, 23:00~24:00
private const val DEFAULT_DISMISSAL_MINUTES = 16 * 60 + 30 // NICE 시간표를 못 가져올 때 쓰는 하교시각 폴백값 (16:30)
private const val DEFAULT_BREAK_MINUTES = 10

/** 학습 불가 시간의 종류 — 바쁜 시간 조회 API 가 그대로 내려준다 */
enum class UnavailableType { SLEEP, SCHOOL, UNAVAILABLE }

data class TypedRange(val type: UnavailableType, val start: Int, val end: Int)

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
     * 취침시간 기본값을 항상 채우고, 평일(학교 가는 날)은 **자정부터 하교 시각까지 통째로** 막는다.
     * → 평일에는 하교 후에만, 주말에는 아침(06:30)부터 계획이 잡힌다.
     *   공휴일·방학·재량휴업일처럼 학교를 안 가는 날(NICE 학사일정, [SchoolDataFetcher.getNoSchoolDays])은 평일이어도 주말처럼 취급한다.
     *   (예전엔 등교 준비 07:00~08:00 · 학교 08:30~ 만 막아서 06:30~07:00, 08:00~08:30 틈에 짧은 태스크가 들어갔음)
     * 사용자가 직접 등록한 시간대(customRows)는 그 위에 더한다. 하교시각은 NICE 실시간 시간표로 그날의
     * 마지막 교시를 조회해 계산한다 (실패/데이터없음 시 기본값 16:30 으로 폴백).
     */
    suspend fun buildUnavailableRanges(
        schoolDataFetcher: SchoolDataFetcher,
        startDate: LocalDate,
        endDate: LocalDate,
        schoolId: String?,
        classNumber: Int?,
        grade: Int,
        customRows: List<Pair<Int, Pair<Int, Int>>>
    ): Map<LocalDate, List<Pair<Int, Int>>> =
        buildTypedUnavailableRanges(schoolDataFetcher, startDate, endDate, schoolId, classNumber, grade, customRows)
            .mapValues { (_, ranges) -> ranges.map { it.start to it.end } }

    /** [buildUnavailableRanges] 와 같은 계산이지만 각 시간이 수면·등교·사용자 등록 중 무엇인지 함께 돌려준다. */
    suspend fun buildTypedUnavailableRanges(
        schoolDataFetcher: SchoolDataFetcher,
        startDate: LocalDate,
        endDate: LocalDate,
        schoolId: String?,
        classNumber: Int?,
        grade: Int,
        customRows: List<Pair<Int, Pair<Int, Int>>>
    ): Map<LocalDate, List<TypedRange>> {
        val dates = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.toList()
        val customByWeekday = customRows.groupBy({ it.first }, { it.second })

        val dismissalMinutesByDate = if (schoolId != null) {
            runCatching { fetchDismissalMinutesByDate(schoolDataFetcher, schoolId, classNumber, grade, startDate, endDate) }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }
        // 공휴일·방학·재량휴업일 등 학교 안 가는 날 (NICE 학사일정). 학교 정보가 없거나 조회 실패면 평일은 전부 등교일로 본다
        val noSchoolDays = if (schoolId != null) {
            runCatching { schoolDataFetcher.getNoSchoolDays(schoolId, startDate, endDate) }.getOrElse { emptySet() }
        } else {
            emptySet()
        }

        return dates.associateWith { date ->
            val ranges = DEFAULT_UNAVAILABLE_RANGES.map { (start, end) -> TypedRange(UnavailableType.SLEEP, start, end) }.toMutableList()
            // 등교일(평일이면서 공휴일·방학 등이 아닌 날): 등교 전 시간을 포함해 하교 시각까지 전부 막는다
            if (date.dayOfWeek.value <= 5 && date !in noSchoolDays) {
                ranges.add(TypedRange(UnavailableType.SCHOOL, 0, dismissalMinutesByDate[date] ?: DEFAULT_DISMISSAL_MINUTES))
            }
            ranges.addAll(customByWeekday[date.dayOfWeek.value].orEmpty().map { (start, end) -> TypedRange(UnavailableType.UNAVAILABLE, start, end) })
            ranges
        }
    }

    /**
     * 날짜별 막힌 시간에 사용자의 기존 할일(다른 플랜보드 포함)을 더한다.
     * 새 할일끼리처럼 기존 할일 앞뒤에도 쉬는 시간을 두려고 [DEFAULT_BREAK_MINUTES] 만큼 넓혀서 막는다.
     */
    fun withExistingTasks(
        unavailableRanges: Map<LocalDate, List<Pair<Int, Int>>>,
        existingTaskRanges: Map<LocalDate, List<Pair<Int, Int>>>
    ): Map<LocalDate, List<Pair<Int, Int>>> =
        unavailableRanges.mapValues { (date, ranges) ->
            ranges + existingTaskRanges[date].orEmpty().map { (start, end) ->
                (start - DEFAULT_BREAK_MINUTES).coerceAtLeast(0) to (end + DEFAULT_BREAK_MINUTES).coerceAtMost(DAY_MINUTES)
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
        // 학기별로 계획 기간에 해당하는 날짜 범위만 조회한다 (학기 전체를 받으면 수백~수천 건)
        val datesBySemester = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .groupBy { academicYearAndSemester(it) }

        val lastPeriodByDate = mutableMapOf<LocalDate, Int>()
        for ((yearAndSemester, dates) in datesBySemester) {
            val (year, semester) = yearAndSemester
            schoolDataFetcher.getTimetable(schoolId, year, semester, grade, className, from = dates.first(), to = dates.last()).forEach { entry ->
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
