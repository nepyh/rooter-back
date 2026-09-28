import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
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
    val mondayEvening = 1 to ((18 * 60) to (20 * 60)) // 월 18:00~20:00

    suspend fun buildRanges(customRows: List<Pair<Int, Pair<Int, Int>>>) =
        PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = monday,
            endDate = sunday,
            schoolId = null,
            classNumber = null,
            grade = 1,
            customRows = customRows
        )

    "학교가 있으면 계획 기간의 시간표만 조회해서, 그날 마지막 교시로 하교 시각을 계산한다" {
        val requestedRanges = mutableListOf<Pair<String?, String?>>()
        val timetableFetcher = SchoolDataFetcher(
            NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { request ->
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
            grade = 1,
            customRows = emptyList()
        )

        requestedRanges shouldBe listOf("20260928" to "20261004")
        ranges.getValue(monday) shouldContainAll listOf((8 * 60 + 30) to (15 * 60 + 10)) // 6교시 → 15:10
        ranges.getValue(monday.plusDays(1)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 10)) // 7교시 → 16:10
        ranges.getValue(monday.plusDays(2)) shouldContainAll listOf((8 * 60 + 30) to (16 * 60 + 30)) // 데이터 없음 → 기본값
    }

    "직접 등록한 불가 시간이 있어도 취침시간·학교시간 기본값은 유지된다" {
        val ranges = buildRanges(listOf(mondayEvening))

        ranges.getValue(monday) shouldContainAll listOf(
            0 to (6 * 60 + 30),
            (23 * 60) to (24 * 60),
            (7 * 60) to (8 * 60),
            (8 * 60 + 30) to (16 * 60 + 30),
            (18 * 60) to (20 * 60)
        )
        // 등록 안 한 요일도 기본값이 그대로 들어간다
        ranges.getValue(monday.plusDays(1)) shouldContainAll listOf(
            0 to (6 * 60 + 30),
            (8 * 60 + 30) to (16 * 60 + 30)
        )
    }

    "직접 등록한 불가 시간이 있어도 태스크가 새벽 00:00 에 배치되지 않는다" {
        val ranges = buildRanges(listOf(mondayEvening))

        val mondayTasks = PlanTaskScheduler.placeTasks(
            listOf("A" to 60, "B" to 60),
            PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(monday))
        )
        mondayTasks[0].startTime shouldBe LocalTime.of(16, 30)
        mondayTasks[1].startTime shouldBe LocalTime.of(20, 0) // 16:30~17:30 뒤 18:00~20:00 은 막혀 있음

        val saturdayTasks = PlanTaskScheduler.placeTasks(
            listOf("A" to 60),
            PlanTaskScheduler.freeIntervalsFromBusyRanges(ranges.getValue(monday.plusDays(5)))
        )
        saturdayTasks[0].startTime shouldBe LocalTime.of(6, 30)
    }
})
