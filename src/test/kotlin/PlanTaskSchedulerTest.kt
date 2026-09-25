import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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
