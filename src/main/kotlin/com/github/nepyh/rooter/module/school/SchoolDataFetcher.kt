package com.github.nepyh.rooter.module.school

import com.github.nepyh.rooter.module.school.model.School
import com.github.nepyh.rooter.module.school.model.SchoolEvent
import com.github.nepyh.rooter.module.school.model.TimetableEntry
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import com.github.nepyh.rooter.module.school.model.SchoolKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 학교 데이터 fetcher.
 * NICE Open API 를 래핑해 서비스 도메인에 학교 데이터(학교 검색, 시간표, 학사일정, 학급 정보)를 제공한다.
 *
 * - 중학교 전용 (misTimetable)
 * - 모든 메서드는 비동기(suspend) — NICE 호출은 Ktor HttpClient 기반
 * - 교시별 시각 매핑은 planboard 도메인 책임 (fetcher 는 "N교시 = 과목" 원본만 제공)
 */
class SchoolDataFetcher(
    private val niceClient: NiceApiClient,
    clock: Clock = Clock.systemUTC(),
    refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private val EXAM_KEYWORDS = listOf("고사", "시험")

        /** 학사일정 SBTR_DD_SC_NM 중 학교를 안 가는 날. 공휴일(추석·대체공휴일 등), 휴업일(방학·재량휴업일·개교기념일·토요휴업일) */
        private val NO_SCHOOL_DAY_TYPES = setOf("공휴일", "휴업일")

        // 백그라운드 갱신 주기 — 시간표는 학교가 학기 중에 고칠 수 있고, 학사일정은 거의 안 바뀐다
        val TIMETABLE_REFRESH: Duration = Duration.ofHours(12)
        // 아직 등록 전일 수 있는 미래 날짜의 빈 시간표는 자주 다시 본다 (12시간이면 등록 후에도 기본 하교 16:30 으로 오래 계산)
        val EMPTY_FUTURE_TIMETABLE_REFRESH: Duration = Duration.ofHours(1)
        val NO_SCHOOL_REFRESH: Duration = Duration.ofHours(24)
    }

    /** 시간표 캐시에서 날짜를 뺀 나머지 키 */
    private data class TimetableGroup(val schoolId: String, val year: Int, val semester: Int, val grade: Int, val className: String?)

    // 계획 배치(PlanTaskScheduler)가 바쁜 시간 조회·계획 생성·챗봇·복습 배치마다 부르는 두 조회를 날짜 단위로 캐시한다 (DailyCache)
    private val timetableCache = DailyCache<TimetableGroup, List<TimetableEntry>>(
        name = "시간표",
        emptyValue = emptyList(),
        refreshAfter = { date, entries, today ->
            if (entries.isEmpty() && !date.isBefore(today)) EMPTY_FUTURE_TIMETABLE_REFRESH else TIMETABLE_REFRESH
        },
        clock = clock,
        refreshScope = refreshScope
    )
    private val noSchoolCache = DailyCache<String, Boolean>(
        name = "휴업일",
        emptyValue = false,
        refreshAfter = { _, _, _ -> NO_SCHOOL_REFRESH },
        clock = clock,
        refreshScope = refreshScope
    )

    /** 테스트용 — 캐시의 백그라운드 갱신이 끝날 때까지 기다린다 */
    internal suspend fun awaitCacheRefreshes() {
        timetableCache.awaitRefreshes()
        noSchoolCache.awaitRefreshes()
    }

    /**
     * 학교명으로 중학교를 검색한다. (부분 일치)
     * 결과가 없으면 빈 목록을 반환한다.
     */
    suspend fun searchSchools(name: String, kind: SchoolKind = SchoolKind.MIDDLE, limit: Int = 20): List<School> {
        val params = mapOf(
            "SCHUL_NM" to name,
            "SCHUL_KND_SC_NM" to kind.code,
            "pSize" to limit.coerceIn(1, 100).toString()
        )
        return niceClient.getRows("schoolInfo", params, SchoolRow.serializer()).map { row ->
            School.of(
                officeCode = row.officeCode,
                officeName = row.officeName,
                schoolCode = row.schoolCode,
                name = row.name,
                // NICE 가 예상 밖 종류(특수학교 등) 를 주면 요청한 필터 값으로 대체
                kind = SchoolKind.entries.firstOrNull { it.code == row.kind } ?: kind,
                region = row.region,
                foundation = row.foundation
            )
        }
    }

    /**
     * 특정 학년/반의 중학교 시간표를 조회한다.
     * @param schoolId 합성 식별자 (예: "C107181084")
     * @param className 반 — 없으면 해당 학년 전체 반의 시간표가 반환됨 (학년 단위 기본값)
     * @param from, to 조회 기간(TI_FROM_YMD/TI_TO_YMD) — 없으면 학기 전체. 학기 전체는 반 하나만 해도 수백 건이라
     *   필요한 기간만 거는 게 좋다. 100건을 넘어도 모든 페이지를 모은다.
     */
    suspend fun getTimetable(
        schoolId: String,
        year: Int,
        semester: Int,
        grade: Int,
        className: String? = null,
        from: LocalDate? = null,
        to: LocalDate? = null
    ): List<TimetableEntry> {
        // 기간이 있으면 날짜 단위 캐시를 거친다 (기간 없는 학기 전체 조회는 쓰는 곳이 없어 캐시하지 않는다)
        if (from == null || to == null) return fetchTimetable(schoolId, year, semester, grade, className, from, to)
        return timetableCache.get(TimetableGroup(schoolId, year, semester, grade, className), from, to) { rangeFrom, rangeTo ->
            fetchTimetable(schoolId, year, semester, grade, className, rangeFrom, rangeTo).groupBy { it.date }
        }.values.flatten()
    }

    private suspend fun fetchTimetable(
        schoolId: String,
        year: Int,
        semester: Int,
        grade: Int,
        className: String?,
        from: LocalDate?,
        to: LocalDate?
    ): List<TimetableEntry> {
        val (officeCode, schoolCode) = School.splitSchoolId(schoolId)
        val params = buildMap {
            put("ATPT_OFCDC_SC_CODE", officeCode)
            put("SD_SCHUL_CODE", schoolCode)
            put("AY", year.toString())
            put("SEM", semester.toString())
            put("GRADE", grade.toString())
            className?.let { put("CLASS_NM", it) }
            from?.let { put("TI_FROM_YMD", it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
            to?.let { put("TI_TO_YMD", it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
        }
        return niceClient.getAllRows("misTimetable", params, TimetableRow.serializer()).map { row ->
            TimetableEntry(
                date = parseNiceDate(row.date, "ALL_TI_YMD"),
                period = row.period.toIntOrNull() ?: 0,
                subject = row.subject,
                className = row.className
            )
        }
    }

    /**
     * 학교/학년의 반 목록을 조회한다. (반 선택 UI 용)
     */
    suspend fun getClasses(schoolId: String, year: Int, semester: Int, grade: Int): List<String> {
        val (officeCode, schoolCode) = School.splitSchoolId(schoolId)
        val params = mapOf(
            "ATPT_OFCDC_SC_CODE" to officeCode,
            "SD_SCHUL_CODE" to schoolCode,
            "AY" to year.toString(),
            "SEM" to semester.toString(),
            "GRADE" to grade.toString()
        )
        return niceClient.getRows("classInfo", params, ClassInfoRow.serializer()).map { it.className }
    }

    /**
     * 학교 학사일정을 조회한다. (방학, 행사, 일부 학교는 시험기간 포함)
     * @param schoolId 합성 식별자 (예: "C107181084")
     * @param year 학년도 — [year]년 3월 1일 ~ 다음 해 2월 말일
     * @param date 특정 날짜(YYYYMMDD) 필터 — 없으면 학년도 전체
     *
     * SchoolSchedule 은 AY 파라미터를 무시하고 모든 연도를 돌려주기 때문에 AA_FROM_YMD/AA_TO_YMD 로 기간을 건다.
     * 1년치는 100건(1페이지)을 넘으므로 전체 페이지를 모은다.
     */
    suspend fun getSchoolEvents(schoolId: String, year: Int, date: String? = null): List<SchoolEvent> {
        val (officeCode, schoolCode) = School.splitSchoolId(schoolId)
        val academicYearStart = LocalDate.of(year, 3, 1)
        val academicYearEnd = LocalDate.of(year + 1, 3, 1).minusDays(1)
        val params = buildMap {
            put("ATPT_OFCDC_SC_CODE", officeCode)
            put("SD_SCHUL_CODE", schoolCode)
            if (date != null) {
                put("AA_YMD", date)
            } else {
                put("AA_FROM_YMD", academicYearStart.format(DateTimeFormatter.BASIC_ISO_DATE))
                put("AA_TO_YMD", academicYearEnd.format(DateTimeFormatter.BASIC_ISO_DATE))
            }
        }
        return niceClient.getAllRows("SchoolSchedule", params, SchoolEventRow.serializer()).map { row ->
            SchoolEvent(date = parseNiceDate(row.date, "AA_YMD"), name = row.name)
        }
    }

    /**
     * [from]~[to] 중 학교를 안 가는 날(공휴일·방학·재량휴업일 등)을 NICE 학사일정으로 판별한다.
     * 계획 배치에서 이런 날은 평일이어도 주말처럼 취급하려고 쓴다.
     */
    suspend fun getNoSchoolDays(schoolId: String, from: LocalDate, to: LocalDate): Set<LocalDate> =
        noSchoolCache.get(schoolId, from, to) { rangeFrom, rangeTo ->
            fetchNoSchoolDays(schoolId, rangeFrom, rangeTo).associateWith { true }
        }.filterValues { it }.keys

    private suspend fun fetchNoSchoolDays(schoolId: String, from: LocalDate, to: LocalDate): Set<LocalDate> {
        val (officeCode, schoolCode) = School.splitSchoolId(schoolId)
        val params = mapOf(
            "ATPT_OFCDC_SC_CODE" to officeCode,
            "SD_SCHUL_CODE" to schoolCode,
            "AA_FROM_YMD" to from.format(DateTimeFormatter.BASIC_ISO_DATE),
            "AA_TO_YMD" to to.format(DateTimeFormatter.BASIC_ISO_DATE)
        )
        return niceClient.getAllRows("SchoolSchedule", params, SchoolEventRow.serializer())
            .filter { it.dayType in NO_SCHOOL_DAY_TYPES }
            .map { parseNiceDate(it.date, "AA_YMD") }
            .toSet()
    }

    /**
     * 학사일정 중 이벤트명에 시험 관련 키워드("고사", "시험")가 포함된 것만 추린다.
     * NICE 학사일정은 자유 텍스트 이름이라 정확한 분류가 아니라, examDate 입력을 돕는 추천 후보일 뿐 —
     * 최종 확정은 사용자가 한다.
     */
    suspend fun getExamScheduleCandidates(schoolId: String, year: Int): List<SchoolEvent> =
        getSchoolEvents(schoolId, year).filter { event -> EXAM_KEYWORDS.any { event.name.contains(it) } }

    /** NICE 날짜(YYYYMMDD) 를 LocalDate 로 파싱한다. 형식 오류는 조용히 넘기지 않고 예외로 드러낸다. */
    private fun parseNiceDate(raw: String, field: String): LocalDate {
        return try {
            LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: DateTimeParseException) {
            throw NiceApiException.UnexpectedResponseException("NICE 응답의 날짜 형식이 올바르지 않습니다 ($field): $raw")
        }
    }
}
