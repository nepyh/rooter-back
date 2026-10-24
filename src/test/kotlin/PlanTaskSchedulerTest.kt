import com.github.nepyh.rooter.module.planboard.PlacedTask
import com.github.nepyh.rooter.module.planboard.PlacementOptions
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.placementOptions
import com.github.nepyh.rooter.module.planboard.RoutineSlot
import com.github.nepyh.rooter.module.planboard.SleepTime
import com.github.nepyh.rooter.module.planboard.TypedRange
import com.github.nepyh.rooter.module.planboard.UnavailableType
import com.github.nepyh.rooter.module.planboard.UserRoutine
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.BestTime
import com.github.nepyh.rooter.module.studystyle.Distraction
import com.github.nepyh.rooter.module.studystyle.FocusSpan
import com.github.nepyh.rooter.module.studystyle.StudyStyleProfile
import com.github.nepyh.rooter.module.studystyle.VolatileTime
import com.github.nepyh.rooter.module.user.model.UnavailableKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import java.time.LocalTime

class PlanTaskSchedulerTest : StringSpec({

    // schoolId = null 이면 NICE 를 호출하지 않으므로, 호출되면 바로 실패하게 둔다
    val schoolDataFetcher = SchoolDataFetcher(
        NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") }))
    )

    val monday = LocalDate.of(2026, 9, 28)
    val sunday = LocalDate.of(2026, 10, 4)
    val mondayEvening = RoutineSlot(1, UnavailableKind.ACADEMY, 18 * 60, 20 * 60) // 월 18:00~20:00 학원
    // 기본 식사: 아침 기상(06:30) 후 30분, 점심 12:00~13:00, 저녁 18:00~19:00
    val defaultMeals = listOf((6 * 60 + 30) to (7 * 60), (12 * 60) to (13 * 60), (18 * 60) to (19 * 60))

    suspend fun buildRanges(slots: List<RoutineSlot>, sleepTime: SleepTime = SleepTime.DEFAULT, commuteMinutes: Int = UserRoutine.DEFAULT_COMMUTE_MINUTES) =
        PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = monday,
            endDate = sunday,
            schoolId = null,
            classNumber = null,
            grade = 1,
            routine = UserRoutine(sleepTime, commuteMinutes, slots)
        )

    "사용자 수면시간: 자정 전에 자면 00:00~기상, 취침~24:00 두 구간을 막고 주말엔 아침 식사 뒤부터 잡힌다" {
        val ranges = buildRanges(emptyList(), SleepTime(LocalTime.of(22, 30), LocalTime.of(8, 0)))
        val saturday = ranges.getValue(monday.plusDays(5))

        // 아침 기본값은 기상(08:00) 후 30분
        saturday shouldBe listOf(0 to (8 * 60), (22 * 60 + 30) to (24 * 60), (8 * 60) to (8 * 60 + 30), (12 * 60) to (13 * 60), (18 * 60) to (19 * 60))
        PlanTaskScheduler.placeTasks(listOf("A" to 60), PlanTaskScheduler.freeIntervalsFromBusyRanges(saturday))
            .single().startTime shouldBe LocalTime.of(8, 30)
    }

    "사용자 수면시간: 자정 넘어 자면 취침~기상 한 구간만 막아, 전날 밤 23시대에도 할일이 들어갈 수 있다" {
        val ranges = buildRanges(emptyList(), SleepTime(LocalTime.of(1, 0), LocalTime.of(7, 0)))
        val saturday = ranges.getValue(monday.plusDays(5))

        saturday shouldBe listOf(60 to (7 * 60), (7 * 60) to (7 * 60 + 30), (12 * 60) to (13 * 60), (18 * 60) to (19 * 60))
        PlanTaskScheduler.freeIntervalsFromBusyRanges(saturday).last().second shouldBe 24 * 60
    }

    "SleepTime.ranges: 00:00 기상이면 빈 앞 구간을 넣지 않는다, 기본값은 예전 고정값과 같다" {
        SleepTime(LocalTime.of(22, 0), LocalTime.MIDNIGHT).ranges() shouldBe listOf((22 * 60) to (24 * 60))
        SleepTime.DEFAULT.ranges() shouldBe listOf(0 to (6 * 60 + 30), (23 * 60) to (24 * 60))
    }

    "학교가 있으면 계획 기간의 시간표만 조회해서, 그날 마지막 교시로 하교 시각을 계산한다" {
        val requestedRanges = mutableListOf<Pair<String?, String?>>()
        val timetableFetcher = SchoolDataFetcher(
            NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { request ->
                // 학사일정(쉬는 날 조회)은 이번 주 쉬는 날 없음
                if (request.url.encodedPath.endsWith("SchoolSchedule")) {
                    return@MockEngine respond(
                        content = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                requestedRanges += request.url.parameters["TI_FROM_YMD"] to request.url.parameters["TI_TO_YMD"]
                // 월요일 6교시, 화요일 7교시 (수~금은 시간표 없음 → 기본 하교 16:30)
                respond(
                    content = """
                        {"misTimetable":[{"head":[{"list_total_count":2},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[
                            {"ALL_TI_YMD":"20260928","PERIO":"6","ITRT_CNTNT":"국어","CLASS_NM":"1"},
                            {"ALL_TI_YMD":"20260929","PERIO":"7","ITRT_CNTNT":"수학","CLASS_NM":"1"}
                        ]}]}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }))
        )

        val ranges = PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = timetableFetcher,
            startDate = monday,
            endDate = sunday,
            schoolId = "B107132131",
            classNumber = 1,
            grade = 1
        )

        requestedRanges shouldBe listOf("20260928" to "20261004")
        ranges.getValue(monday) shouldContainAll listOf((8 * 60 + 30) to (15 * 60 + 10)) // 6교시 → 학교 08:30~15:10
        ranges.getValue(monday.plusDays(1)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 10)) // 7교시 → 16:10
        ranges.getValue(monday.plusDays(2)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 30)) // 데이터 없음 → 기본값 16:30
    }

    "직접 등록한 불가 시간이 있어도 취침시간·학교시간 기본값은 유지된다" {
        val ranges = buildRanges(listOf(mondayEvening))

        ranges.getValue(monday) shouldContainAll listOf(
            (6 * 60 + 30) to (8 * 60 + 30), // 평일: 기상~등교 준비, 등교~하교 학교
            (8 * 60 + 30) to (16 * 60 + 30),
            (16 * 60 + 30) to (17 * 60 + 10), // 하교 후 귀가 40분
            (23 * 60) to (24 * 60),
            (18 * 60) to (20 * 60)
        )
        // 등록 안 한 요일도 기본값이 그대로 들어간다
        ranges.getValue(monday.plusDays(1)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 30))
        // 주말은 취침시간과 기본 식사만
        ranges.getValue(monday.plusDays(5)) shouldBe listOf(0 to (6 * 60 + 30), (23 * 60) to (24 * 60)) + defaultMeals
    }

    "직접 등록한 불가 시간이 있어도 태스크가 새벽 00:00 에 배치되지 않는다" {
        val ranges = buildRanges(listOf(mondayEvening))

        val mondayTasks = PlanTaskScheduler.placeTasks(
            listOf("A" to 40, "B" to 60),
            PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(monday))
        )
        mondayTasks[0].startTime shouldBe LocalTime.of(17, 10) // 하교 16:30 + 귀가 40분
        mondayTasks[1].startTime shouldBe LocalTime.of(20, 0) // 17:10~17:50 뒤 18:00~20:00 은 학원(저녁 기본값과 겹침)

        val saturdayTasks = PlanTaskScheduler.placeTasks(
            listOf("A" to 60),
            PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(monday.plusDays(5)))
        )
        saturdayTasks[0].startTime shouldBe LocalTime.of(7, 0) // 06:30 기상 + 아침 30분
    }

    "평일엔 짧은 태스크도 등교 전(06:30~07:00, 08:00~08:30)에 안 들어가고 귀가 후에만, 주말엔 아침 식사 뒤부터 들어간다" {
        val ranges = buildRanges(emptyList())
        val shortTasks = listOf("A" to 20, "B" to 30, "C" to 20)

        // 17:10~18:00 에 A 가 들어가고, B 는 저녁(18:00~19:00) 에 걸려 저녁 뒤로 간다
        val weekday = PlanTaskScheduler.placeTasks(shortTasks, PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(monday)))
        weekday.map { it.startTime } shouldBe listOf(LocalTime.of(17, 10), LocalTime.of(19, 0), LocalTime.of(19, 40))

        val saturday = PlanTaskScheduler.placeTasks(shortTasks, PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(monday.plusDays(5))))
        saturday.first().startTime shouldBe LocalTime.of(7, 0)
    }

    "등교일엔 하교 뒤 귀가 시간을 막고, 귀가 시간을 0 으로 저장하면 하교하자마자 잡을 수 있다" {
        val withCommute = buildRanges(emptyList(), commuteMinutes = 60)
        withCommute.getValue(monday) shouldContainAll listOf((16 * 60 + 30) to (17 * 60 + 30))
        withCommute.getValue(monday.plusDays(5)).none { it.first == 16 * 60 + 30 } shouldBe true // 주말엔 귀가 시간 없음

        val noCommute = buildRanges(emptyList(), commuteMinutes = 0)
        PlanTaskScheduler.placeTasks(listOf("A" to 60), PlanTaskScheduler.freeIntervalsFromBusyRanges(noCommute.getValue(monday)))
            .single().startTime shouldBe LocalTime.of(16, 30)
    }

    "식사를 등록한 요일은 그 시간을, 등록 안 한 요일·끼니는 기본값을 막는다" {
        val mondayDinner = RoutineSlot(1, UnavailableKind.DINNER, 19 * 60, 20 * 60) // 월 저녁 19:00~20:00
        val saturdayLunch = RoutineSlot(6, UnavailableKind.LUNCH, 12 * 60 + 30, 13 * 60 + 30)
        val ranges = buildRanges(listOf(mondayDinner, saturdayLunch))

        ranges.getValue(monday) shouldContainAll listOf((19 * 60) to (20 * 60))
        ranges.getValue(monday).contains((18 * 60) to (19 * 60)) shouldBe false // 등록한 저녁이 기본 저녁을 대신한다
        ranges.getValue(monday.plusDays(1)) shouldContainAll listOf((18 * 60) to (19 * 60)) // 화요일은 기본 저녁
        ranges.getValue(monday.plusDays(5)) shouldBe listOf(
            0 to (6 * 60 + 30), (23 * 60) to (24 * 60),
            (6 * 60 + 30) to (7 * 60), (12 * 60 + 30) to (13 * 60 + 30), (18 * 60) to (19 * 60)
        )
    }

    "등교일엔 학교 시간 안의 아침·점심을 빼고 저녁만, 쉬는 날엔 세 끼 모두 이름을 붙여 막는다" {
        val typed = PlanTaskScheduler.buildTypedUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = monday,
            endDate = sunday,
            schoolId = null,
            classNumber = null,
            grade = 1
        )

        typed.getValue(monday).filter { it.type == UnavailableType.MEAL } shouldBe listOf(
            TypedRange(UnavailableType.MEAL, 18 * 60, 19 * 60, "저녁 식사")
        )
        typed.getValue(monday.plusDays(5)).filter { it.type == UnavailableType.MEAL } shouldBe listOf(
            TypedRange(UnavailableType.MEAL, 6 * 60 + 30, 7 * 60, "아침 식사"),
            TypedRange(UnavailableType.MEAL, 12 * 60, 13 * 60, "점심 식사"),
            TypedRange(UnavailableType.MEAL, 18 * 60, 19 * 60, "저녁 식사")
        )
    }

    "하교가 이른 날(시험 기간 등)엔 하교 뒤에 걸치는 점심은 남긴다" {
        UserRoutine().ranges(1, schoolEnd = 12 * 60 + 10).map { it.label to (it.start to it.end) } shouldBe listOf(
            "점심 식사" to ((12 * 60) to (13 * 60)),
            "저녁 식사" to ((18 * 60) to (19 * 60))
        )
    }

    "등록한 불가능 시간은 붙인 이름을, 이름이 없으면 끼니·종류 이름을 쓴다" {
        val routine = UserRoutine(
            slots = listOf(
                RoutineSlot(6, UnavailableKind.DINNER, 19 * 60, 20 * 60, "가족 저녁"),
                RoutineSlot(6, UnavailableKind.ACADEMY, 14 * 60, 16 * 60),
                RoutineSlot(6, UnavailableKind.CUSTOM, 16 * 60, 17 * 60, "피아노")
            )
        )

        routine.ranges(6).map { it.label } shouldBe listOf("아침 식사", "점심 식사", "가족 저녁", "학원", "피아노")
    }

    "막힌 시간마다 종류를 붙인다 — 귀가 COMMUTE, 식사 MEAL, 학원·기타 UNAVAILABLE" {
        val typed = PlanTaskScheduler.buildTypedUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = monday,
            endDate = monday,
            schoolId = null,
            classNumber = null,
            grade = 1,
            routine = UserRoutine(slots = listOf(mondayEvening))
        ).getValue(monday)

        typed shouldBe listOf(
            TypedRange(UnavailableType.SLEEP, 0, 6 * 60 + 30),
            TypedRange(UnavailableType.SLEEP, 23 * 60, 24 * 60),
            TypedRange(UnavailableType.SCHOOL_PREP, 6 * 60 + 30, 8 * 60 + 30),
            TypedRange(UnavailableType.SCHOOL, 8 * 60 + 30, 16 * 60 + 30),
            TypedRange(UnavailableType.COMMUTE, 16 * 60 + 30, 17 * 60 + 10),
            TypedRange(UnavailableType.MEAL, 18 * 60, 19 * 60, "저녁 식사"),
            TypedRange(UnavailableType.UNAVAILABLE, 18 * 60, 20 * 60, "학원")
        )
        typed.map { it.label } shouldBe listOf("수면", "수면", "등교 준비", "학교", "하교 후 귀가", "저녁 식사", "학원")
    }

    "자정 넘어 자는 학생도 등교일 00:00~취침·기상~08:30 을 등교 준비로 막아, 평일 새벽·아침엔 할일이 안 들어간다" {
        val typed = PlanTaskScheduler.buildTypedUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = monday,
            endDate = monday,
            schoolId = null,
            classNumber = null,
            grade = 1,
            routine = UserRoutine(sleepTime = SleepTime(LocalTime.of(1, 0), LocalTime.of(7, 30)))
        ).getValue(monday)

        typed.filter { it.type == UnavailableType.SCHOOL_PREP }.map { it.start to it.end } shouldBe
            listOf(0 to 60, (7 * 60 + 30) to (8 * 60 + 30))
        PlanTaskScheduler.freeIntervalsFromBusyRanges(typed.map { it.start to it.end }).first().first shouldBe 17 * 60 + 10
    }

    "공휴일·방학처럼 학교 안 가는 날은 평일이어도 주말처럼 아침부터 계획이 잡힌다" {
        val holidayMonday = LocalDate.of(2026, 10, 5) // 대체공휴일
        val fetcher = SchoolDataFetcher(
            NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { request ->
                val body = if (request.url.encodedPath.endsWith("SchoolSchedule")) {
                    """
                    {"SchoolSchedule":[{"head":[{"list_total_count":3},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[
                        {"AA_YMD":"20261005","EVENT_NM":"대체공휴일","SBTR_DD_SC_NM":"공휴일"},
                        {"AA_YMD":"20261007","EVENT_NM":"중간고사","SBTR_DD_SC_NM":"해당없음"},
                        {"AA_YMD":"20261009","EVENT_NM":"한글날","SBTR_DD_SC_NM":"공휴일"}
                    ]}]}
                    """.trimIndent()
                } else {
                    """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""
                }
                respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }))
        )

        val ranges = PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = fetcher,
            startDate = holidayMonday,
            endDate = holidayMonday.plusDays(6),
            schoolId = "B107132131",
            classNumber = 1,
            grade = 1
        )

        val noSchoolDay = listOf(0 to (6 * 60 + 30), (23 * 60) to (24 * 60)) + defaultMeals
        ranges.getValue(holidayMonday) shouldBe noSchoolDay                                  // 대체공휴일 → 주말처럼
        ranges.getValue(LocalDate.of(2026, 10, 9)) shouldBe noSchoolDay                     // 한글날(금)
        ranges.getValue(LocalDate.of(2026, 10, 6)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 30)) // 평일 등교일은 그대로
        ranges.getValue(LocalDate.of(2026, 10, 7)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 30)) // 행사 있어도 등교일

        val holidayTasks = PlanTaskScheduler.placeTasks(listOf("A" to 30), PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(holidayMonday)))
        holidayTasks.single().startTime shouldBe LocalTime.of(7, 0)
    }

    // ── 공부 스타일 설문 배치 (PlacementOptions) ──
    // 평일 등교일: 하교 16:30, 취침 23:00 → 빈 시간 16:30~23:00
    val schoolDayFree = PlanTaskScheduler.freeIntervalsFromBusyRanges(listOf(0 to (16 * 60 + 30), (23 * 60) to (24 * 60)))
    fun times(placed: List<PlacedTask>) = placed.map { it.startTime to it.endTime }
    fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    "배치 옵션을 안 주면 예전처럼 하교 직후부터 10분씩 쉬며 채운다" {
        times(PlanTaskScheduler.placeTasks(listOf("A" to 50, "B" to 30), schoolDayFree)) shouldBe
            listOf(t(16, 30) to t(17, 20), t(17, 30) to t(18, 0))
    }

    "쉬는 시간을 바꾸면 태스크 사이 간격이 바뀐다" {
        times(PlanTaskScheduler.placeTasks(listOf("A" to 30, "B" to 30), schoolDayFree, PlacementOptions(breakMinutes = 5))) shouldBe
            listOf(t(16, 30) to t(17, 0), t(17, 5) to t(17, 35))
    }

    "밤부터 채우면 잠들기 전에 몰아 두되 태스크 순서는 유지한다" {
        val placed = PlanTaskScheduler.placeTasks(listOf("A" to 60, "B" to 30), schoolDayFree, PlacementOptions(fromLate = true))

        placed.map { it.taskName } shouldBe listOf("A", "B")
        times(placed) shouldBe listOf(t(21, 20) to t(22, 20), t(22, 30) to t(23, 0))
    }

    "밤부터 채울 때 전부 안 들어가면 앞쪽 태스크가 빠지지 않게 이른 시간부터 채운다" {
        val free = listOf((20 * 60) to (21 * 60)) // 1시간뿐
        val placed = PlanTaskScheduler.placeTasks(listOf("A" to 40, "B" to 40), free, PlacementOptions(fromLate = true))

        placed.map { it.taskName } shouldBe listOf("A")
        times(placed) shouldBe listOf(t(20, 0) to t(20, 40))
    }

    "피할 구간을 빼고도 다 들어가면 피하고, 안 들어가면 무시한다" {
        val avoidAfterSchool = PlacementOptions(avoidRanges = listOf(0 to (18 * 60 + 30)))

        times(PlanTaskScheduler.placeTasks(listOf("A" to 60), schoolDayFree, avoidAfterSchool)) shouldBe listOf(t(18, 30) to t(19, 30))
        // 18:30~23:00 (270분) 에 안 들어가는 분량이면 하교 직후부터 원래대로
        PlanTaskScheduler.placeTasks(listOf("A" to 150, "B" to 150), schoolDayFree, avoidAfterSchool)
            .first().startTime shouldBe t(16, 30)
    }

    "설문 → 배치 옵션: 집중 시간은 쉬는 시간, 밤은 뒤부터, 방과 후는 등교일만 피한다" {
        val bed = SleepTime.DEFAULT // 23:00 취침

        StudyStyleProfile.EMPTY.placementOptions(isSchoolDay = true, sleepTime = bed) shouldBe PlacementOptions.DEFAULT
        StudyStyleProfile(focus = FocusSpan.SHORT).placementOptions(true, bed).breakMinutes shouldBe 5
        StudyStyleProfile(focus = FocusSpan.DEEP).placementOptions(true, bed).breakMinutes shouldBe 15
        StudyStyleProfile(bestTime = BestTime.NIGHT).placementOptions(true, bed).fromLate shouldBe true
        StudyStyleProfile(bestTime = BestTime.MORNING).placementOptions(true, bed).fromLate shouldBe false

        val afterSchool = StudyStyleProfile(volatileTime = VolatileTime.AFTER_SCHOOL)
        afterSchool.placementOptions(isSchoolDay = true, sleepTime = bed).avoidRanges shouldBe listOf(0 to (18 * 60 + 30))
        afterSchool.placementOptions(isSchoolDay = false, sleepTime = bed).avoidRanges shouldBe emptyList()

        StudyStyleProfile(volatileTime = VolatileTime.EVENING).placementOptions(false, bed).avoidRanges shouldBe
            listOf((19 * 60 + 30) to (23 * 60))
    }

    "설문 → 배치 옵션: 졸음이면 취침 전 1시간을 피하되, 밤을 골랐으면 밤 선택을 따른다" {
        val bed = SleepTime.DEFAULT

        StudyStyleProfile(distraction = Distraction.SLEEPY).placementOptions(true, bed).avoidRanges shouldBe listOf((22 * 60) to (23 * 60))
        StudyStyleProfile(distraction = Distraction.SLEEPY, bestTime = BestTime.NIGHT).placementOptions(true, bed).avoidRanges shouldBe emptyList()
        // 00:30 취침이면 전날 밤 23:30~24:00 과 00:00~00:30
        StudyStyleProfile(distraction = Distraction.SLEEPY).placementOptions(true, SleepTime(t(0, 30), t(7))).avoidRanges shouldBe
            listOf(0 to 30, (23 * 60 + 30) to (24 * 60))
    }
})
