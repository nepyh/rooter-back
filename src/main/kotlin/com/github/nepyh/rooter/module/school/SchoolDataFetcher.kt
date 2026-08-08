package com.github.nepyh.rooter.module.school

import com.github.nepyh.rooter.module.school.dto.School
import com.github.nepyh.rooter.module.school.dto.SchoolEvent
import com.github.nepyh.rooter.module.school.dto.TimetableEntry
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import com.github.nepyh.rooter.module.school.model.SchoolKind

/**
 * 학교 데이터 fetcher.
 * NICE Open API 를 래핑해 서비스 도메인에 학교 데이터(학교 검색, 시간표, 학사일정, 학급 정보)를 제공한다.
 *
 * - 중학교 전용 (misTimetable)
 * - 모든 메서드는 비동기(suspend) — NICE 호출은 Ktor HttpClient 기반
 * - 교시별 시각 매핑은 planboard 도메인 책임 (fetcher 는 "N교시 = 과목" 원본만 제공)
 * - 시험일정은 fetcher 범위 밖 (school_exam_periods 크라우드소싱으로 처리)
 */
class SchoolDataFetcher(
    private val niceClient: NiceApiClient
) {

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
                kind = row.kind,
                region = row.region,
                foundation = row.foundation
            )
        }
    }

    /**
     * 특정 학년/반의 중학교 시간표를 조회한다.
     * @param schoolId 합성 식별자 (예: "C107181084")
     * @param className 반 — 없으면 해당 학년 전체 반의 시간표가 반환됨 (학년 단위 기본값)
     */
    suspend fun getTimetable(
        schoolId: String,
        year: Int,
        semester: Int,
        grade: Int,
        className: String? = null
    ): List<TimetableEntry> {
        val (officeCode, schoolCode) = School.splitSchoolId(schoolId)
        val params = buildMap {
            put("ATPT_OFCDC_SC_CODE", officeCode)
            put("SD_SCHUL_CODE", schoolCode)
            put("AY", year.toString())
            put("SEM", semester.toString())
            put("GRADE", grade.toString())
            className?.let { put("CLASS_NM", it) }
        }
        return niceClient.getRows("misTimetable", params, TimetableRow.serializer()).map { row ->
            TimetableEntry(
                date = row.date,
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
     * @param year 학년도
     * @param date 특정 날짜(YYYYMMDD) 필터 — 없으면 학년도 전체
     */
    suspend fun getSchoolEvents(schoolId: String, year: Int, date: String? = null): List<SchoolEvent> {
        val (officeCode, schoolCode) = School.splitSchoolId(schoolId)
        val params = buildMap {
            put("ATPT_OFCDC_SC_CODE", officeCode)
            put("SD_SCHUL_CODE", schoolCode)
            put("AY", year.toString())
            date?.let { put("AA_YMD", it) }
        }
        return niceClient.getRows("SchoolSchedule", params, SchoolEventRow.serializer()).map { row ->
            SchoolEvent(date = row.date, name = row.name)
        }
    }
}
