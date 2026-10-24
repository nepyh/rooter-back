package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalTime

private const val DAY_MINUTES = 24 * 60
private const val DEFAULT_DISMISSAL_MINUTES = 16 * 60 + 30 // NICE 시간표를 못 가져올 때 쓰는 하교시각 폴백값 (16:30)
private const val SCHOOL_START_MINUTES = 8 * 60 + 30 // 등교 시각 (08:30 고정 — NICE 에 학교별 등교 시각이 없음)
private const val DEFAULT_BREAK_MINUTES = 10

/** 학습 불가 시간의 종류 — 바쁜 시간 조회 API 가 그대로 내려준다. [defaultLabel] 은 앱에 보여줄 기본 이름 */
enum class UnavailableType(val defaultLabel: String) {
    SLEEP("수면"), SCHOOL_PREP("등교 준비"), SCHOOL("학교"), COMMUTE("하교 후 귀가"), MEAL("식사"), UNAVAILABLE("개인 일정")
}

/** @property label 앱에 보여줄 이름 — 식사는 아침 식사·점심 식사·저녁 식사, 사용자가 등록한 시간은 붙인 이름 (없으면 학원·개인 일정) */
data class TypedRange(val type: UnavailableType, val start: Int, val end: Int, val label: String = type.defaultLabel)

data class PlacedTask(
    val taskName: String,
    val estimatedMinutes: Int,
    val startTime: LocalTime,
    val endTime: LocalTime
)

/**
 * [PlanTaskScheduler.placeTasks] 의 배치 방식. 공부 스타일 설문으로 정한다 (planboard/StudyStylePlacement.kt).
 * @property breakMinutes 태스크 사이 쉬는 시간
 * @property fromLate true 면 가장 늦은 빈 시간(잠들기 전)부터 채운다
 * @property avoidRanges 다른 빈 시간이 충분하면 피할 구간 (분)
 */
data class PlacementOptions(
    val breakMinutes: Int = DEFAULT_BREAK_MINUTES,
    val fromLate: Boolean = false,
    val avoidRanges: List<Pair<Int, Int>> = emptyList()
) {
    companion object {
        val DEFAULT = PlacementOptions()
    }
}

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
     * 사용자 수면시간([UserRoutine.sleepTime], 설정 안 했으면 23:00~06:30)을 항상 채우고, 평일(학교 가는 날)은 **자정부터 하교 시각까지 통째로** 막는다.
     *   자는 시간을 뺀 등교(08:30) 전은 SCHOOL_PREP(등교 준비), 08:30~하교는 SCHOOL 로 나눠 낸다 (막히는 범위는 같음).
     * → 평일에는 하교 후에만, 주말에는 아침(기상 시각)부터 계획이 잡힌다.
     *   공휴일·방학·재량휴업일처럼 학교를 안 가는 날(NICE 학사일정, [SchoolDataFetcher.getNoSchoolDays])은 평일이어도 주말처럼 취급한다.
     *   (예전엔 등교 준비 07:00~08:00 · 학교 08:30~ 만 막아서 06:30~07:00, 08:00~08:30 틈에 짧은 태스크가 들어갔음)
     * 등교일은 하교 뒤 귀가 시간([UserRoutine.commuteMinutes], 기본 40분)도 막아서 하교하자마자 할일이 붙지 않게 한다.
     * 식사(등록 안 했으면 기본값)와 사용자가 등록한 학원·기타 일정은 그 위에 더한다 ([UserRoutine.ranges]).
     *   등교일의 아침·점심처럼 학교 시간 안에 다 들어가는 식사는 이미 막혀 있으니 따로 넣지 않는다 → 평일엔 저녁만, 쉬는 날엔 세 끼 모두.
     * 하교시각은 NICE 실시간 시간표로 그날의 마지막 교시를 조회해 계산한다 (실패/데이터없음 시 기본값 16:30 으로 폴백).
     */
    suspend fun buildUnavailableRanges(
        schoolDataFetcher: SchoolDataFetcher,
        startDate: LocalDate,
        endDate: LocalDate,
        schoolId: String?,
        classNumber: Int?,
        grade: Int,
        routine: UserRoutine = UserRoutine.DEFAULT
    ): Map<LocalDate, List<Pair<Int, Int>>> =
        buildTypedUnavailableRanges(schoolDataFetcher, startDate, endDate, schoolId, classNumber, grade, routine)
            .mapValues { (_, ranges) -> ranges.map { it.start to it.end } }

    /** [buildUnavailableRanges] 와 같은 계산이지만 각 시간이 수면·등교·귀가·식사·사용자 등록 중 무엇인지 함께 돌려준다. */
    suspend fun buildTypedUnavailableRanges(
        schoolDataFetcher: SchoolDataFetcher,
        startDate: LocalDate,
        endDate: LocalDate,
        schoolId: String?,
        classNumber: Int?,
        grade: Int,
        routine: UserRoutine = UserRoutine.DEFAULT
    ): Map<LocalDate, List<TypedRange>> {
        val dates = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.toList()

        // 시간표(하교 시각)와 학사일정(쉬는 날)은 서로 상관없는 NICE 조회라 동시에 부른다 (순서대로 부르면 캐시가 빈 첫 요청이 둘을 합친 만큼 걸림)
        val (dismissalMinutesByDate, noSchoolDays) = if (schoolId != null) {
            coroutineScope {
                val dismissal = async {
                    runCatching { fetchDismissalMinutesByDate(schoolDataFetcher, schoolId, classNumber, grade, startDate, endDate) }.getOrElse { emptyMap() }
                }
                // 공휴일·방학·재량휴업일 등 학교 안 가는 날 (NICE 학사일정). 조회 실패면 평일은 전부 등교일로 본다
                val noSchool = async {
                    runCatching { schoolDataFetcher.getNoSchoolDays(schoolId, startDate, endDate) }.getOrElse { emptySet() }
                }
                dismissal.await() to noSchool.await()
            }
        } else {
            // 학교 정보가 없으면 하교는 기본값, 평일은 전부 등교일
            emptyMap<LocalDate, Int>() to emptySet<LocalDate>()
        }

        return dates.associateWith { date ->
            val ranges = routine.sleepTime.ranges().map { (start, end) -> TypedRange(UnavailableType.SLEEP, start, end) }.toMutableList()
            // 등교일(평일이면서 공휴일·방학 등이 아닌 날): 등교 전 시간을 포함해 하교 시각까지 전부 막고, 이어서 귀가 시간을 막는다
            val dismissal = if (date.dayOfWeek.value <= 5 && date !in noSchoolDays) {
                dismissalMinutesByDate[date] ?: DEFAULT_DISMISSAL_MINUTES
            } else {
                null
            }
            if (dismissal != null) {
                // 등교 전 깨어 있는 시간(보통 기상~08:30)은 등교 준비로 막아 평일 아침엔 할일이 들어가지 않는다
                val sleepRanges = ranges.map { it.start to it.end }
                subtractRanges(listOf(0 to SCHOOL_START_MINUTES), sleepRanges).forEach { (start, end) ->
                    ranges.add(TypedRange(UnavailableType.SCHOOL_PREP, start, end))
                }
                ranges.add(TypedRange(UnavailableType.SCHOOL, SCHOOL_START_MINUTES, dismissal))
                if (routine.commuteMinutes > 0) {
                    ranges.add(TypedRange(UnavailableType.COMMUTE, dismissal, minOf(dismissal + routine.commuteMinutes, DAY_MINUTES)))
                }
            }
            ranges.addAll(routine.ranges(date.dayOfWeek.value, schoolEnd = dismissal))
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

    /**
     * 태스크를 빈 시간에 순서대로 채운다. [options] 를 생략하면 가장 이른 빈 시간부터, 사이에 10분씩 쉬며 채운다.
     * - [PlacementOptions.avoidRanges]: 이 구간을 뺀 빈 시간에 전부 들어가면 그렇게 배치하고, 안 들어가면 무시한다
     * - [PlacementOptions.fromLate]: 가장 늦은 빈 시간(잠들기 전)부터 거꾸로 채운다 (태스크 순서는 유지).
     *   전부 들어가지 않으면 앞쪽 태스크가 빠지지 않게 원래대로 이른 시간부터 채운다
     * 빈 시간이 모자라면 뒤쪽 태스크는 배치하지 않는다.
     */
    fun placeTasks(
        items: List<Pair<String, Int>>,
        freeIntervals: List<Pair<Int, Int>>,
        options: PlacementOptions = PlacementOptions.DEFAULT
    ): List<PlacedTask> {
        val candidates = listOfNotNull(
            subtractRanges(freeIntervals, options.avoidRanges).takeIf { options.avoidRanges.isNotEmpty() },
            freeIntervals
        )
        for (intervals in candidates) {
            val placed = placeInOrder(items, intervals, options)
            if (placed.size == items.size) return placed
        }
        return placeInOrder(items, freeIntervals, options.copy(fromLate = false))
    }

    private fun placeInOrder(items: List<Pair<String, Int>>, freeIntervals: List<Pair<Int, Int>>, options: PlacementOptions): List<PlacedTask> {
        val slots = if (options.fromLate) {
            // 하루를 거꾸로 뒤집어 앞에서부터 채운 뒤 다시 뒤집는다 → 가장 늦은 빈 시간부터, 마지막 태스크가 가장 늦게
            val mirrored = freeIntervals.map { (start, end) -> (DAY_MINUTES - end) to (DAY_MINUTES - start) }.reversed()
            fillFromEarly(items.reversed(), mirrored, options.breakMinutes)
                .map { (start, end) -> (DAY_MINUTES - end) to (DAY_MINUTES - start) }
                .reversed()
        } else {
            fillFromEarly(items, freeIntervals, options.breakMinutes)
        }
        val placedItems = if (options.fromLate) items.takeLast(slots.size) else items.take(slots.size)
        return placedItems.zip(slots) { (taskName, minutes), (start, end) ->
            PlacedTask(
                taskName = taskName.take(150),
                estimatedMinutes = minutes,
                startTime = minutesToLocalTime(start),
                endTime = minutesToLocalTime(end)
            )
        }
    }

    /** 앞에서부터 채운 각 태스크의 (시작분, 끝분). 빈 시간이 모자라면 거기서 멈춰 앞쪽 태스크만 돌려준다. */
    private fun fillFromEarly(items: List<Pair<String, Int>>, freeIntervals: List<Pair<Int, Int>>, breakMinutes: Int): List<Pair<Int, Int>> {
        if (items.isEmpty() || freeIntervals.isEmpty()) return emptyList()

        var intervalIndex = 0
        var cursor = freeIntervals.getOrNull(0)?.first ?: 0

        val result = mutableListOf<Pair<Int, Int>>()
        for ((_, minutes) in items) {
            while (intervalIndex < freeIntervals.size && cursor + minutes > freeIntervals[intervalIndex].second) {
                intervalIndex++
                cursor = freeIntervals.getOrNull(intervalIndex)?.first ?: cursor
            }
            if (intervalIndex >= freeIntervals.size) break // 남은 빈 시간이 없으면 이후 task는 배치하지 않음

            result.add(cursor to cursor + minutes)
            cursor += minutes + breakMinutes
        }
        return result
    }

    /** [intervals] 에서 [ranges] 와 겹치는 부분을 잘라낸 구간 목록 */
    private fun subtractRanges(intervals: List<Pair<Int, Int>>, ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
        ranges.fold(intervals) { remaining, (cutStart, cutEnd) ->
            remaining.flatMap { (start, end) ->
                listOf(start to minOf(end, cutStart), maxOf(start, cutEnd) to end).filter { (s, e) -> s < e }
            }
        }

    private fun minutesToLocalTime(totalMinutes: Int): LocalTime {
        val clamped = totalMinutes.coerceIn(0, DAY_MINUTES - 1)
        return LocalTime.of(clamped / 60, clamped % 60)
    }
}
