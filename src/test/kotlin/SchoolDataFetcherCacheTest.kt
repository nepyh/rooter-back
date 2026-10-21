import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 테스트에서 시간을 앞으로 감을 수 있는 시계 */
private class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(duration: Duration) { now = now.plus(duration) }
}

class SchoolDataFetcherCacheTest : StringSpec({

    val school = "B107132131"
    val today = LocalDate.of(2026, 10, 21) // 수요일
    fun d(day: Int): LocalDate = LocalDate.of(2026, 10, day)
    fun ymd(date: LocalDate): String = date.format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * 가짜 NICE. 시간표는 요청 기간의 평일마다 [period] 교시 한 줄을 주고([emptyFrom] 이후 날짜는 비움),
     * 학사일정은 [holidays] 를 공휴일로 준다. [failNext] 가 true 면 다음 요청 한 번은 서버 오류.
     */
    class FakeNice {
        val requests = mutableListOf<HttpRequestData>()
        var period = 6
        var emptyFrom: LocalDate? = null
        var holidays = setOf<LocalDate>()
        var failNext = false

        fun timetableRanges() = requests.filter { it.url.encodedPath.endsWith("misTimetable") }
            .map { it.url.parameters["TI_FROM_YMD"] to it.url.parameters["TI_TO_YMD"] }
        fun scheduleCount() = requests.count { it.url.encodedPath.endsWith("SchoolSchedule") }

        val engine = MockEngine { request ->
            requests += request
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (failNext) {
                failNext = false
                return@MockEngine respond("""{"RESULT":{"CODE":"ERROR-500","MESSAGE":"서버 오류"}}""", HttpStatusCode.OK, json)
            }
            val p = request.url.parameters
            if (request.url.encodedPath.endsWith("SchoolSchedule")) {
                val from = LocalDate.parse(p["AA_FROM_YMD"]!!, DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(p["AA_TO_YMD"]!!, DateTimeFormatter.BASIC_ISO_DATE)
                val rows = holidays.filter { !it.isBefore(from) && !it.isAfter(to) }
                    .joinToString(",") { """{"AA_YMD":"${it.format(DateTimeFormatter.BASIC_ISO_DATE)}","EVENT_NM":"휴일","SBTR_DD_SC_NM":"공휴일"}""" }
                return@MockEngine if (rows.isEmpty()) {
                    respond("""{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}""", HttpStatusCode.OK, json)
                } else {
                    respond("""{"SchoolSchedule":[{"head":[{"list_total_count":${rows.count { it == '{' }}},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상"}}]},{"row":[$rows]}]}""", HttpStatusCode.OK, json)
                }
            }
            val from = LocalDate.parse(p["TI_FROM_YMD"]!!, DateTimeFormatter.BASIC_ISO_DATE)
            val to = LocalDate.parse(p["TI_TO_YMD"]!!, DateTimeFormatter.BASIC_ISO_DATE)
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
                .filter { it.dayOfWeek.value <= 5 && (emptyFrom == null || it.isBefore(emptyFrom)) }
                .toList()
            if (days.isEmpty()) {
                respond("""{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}""", HttpStatusCode.OK, json)
            } else {
                val rows = days.joinToString(",") { """{"ALL_TI_YMD":"${it.format(DateTimeFormatter.BASIC_ISO_DATE)}","PERIO":"$period","ITRT_CNTNT":"수학","CLASS_NM":"1"}""" }
                respond("""{"misTimetable":[{"head":[{"list_total_count":${days.size}},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상"}}]},{"row":[$rows]}]}""", HttpStatusCode.OK, json)
            }
        }
    }

    // 한국 시간 2026-10-21 12:00
    fun setup(): Triple<FakeNice, MutableClock, SchoolDataFetcher> {
        val nice = FakeNice()
        val clock = MutableClock(Instant.parse("2026-10-21T03:00:00Z"))
        val fetcher = SchoolDataFetcher(
            NiceApiClient(apiKey = "test-key", httpClient = HttpClient(nice.engine)),
            clock = clock,
            refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        return Triple(nice, clock, fetcher)
    }

    suspend fun SchoolDataFetcher.periods(from: LocalDate, to: LocalDate): Map<LocalDate, Int> =
        getTimetable(school, 2026, 2, 2, "1", from, to).associate { it.date to it.period }

    "같은 기간을 두 번 조회하면 NICE 는 한 번만 부르고 결과는 같다" {
        val (nice, _, fetcher) = setup()

        val first = fetcher.periods(d(19), d(25))
        val second = fetcher.periods(d(19), d(25))

        nice.timetableRanges() shouldBe listOf("20261019" to "20261025")
        second shouldBe first
        first.keys shouldBe setOf(d(19), d(20), d(21), d(22), d(23))
    }

    "날짜 단위로 저장해서, 겹치는 기간은 캐시에 없는 날짜만 NICE 에 묻는다" {
        val (nice, _, fetcher) = setup()

        fetcher.periods(d(19), d(25))
        fetcher.periods(d(21), d(21)) // 하루짜리(홈 화면)는 주간 조회 결과로 해결
        fetcher.periods(d(23), d(30))

        nice.timetableRanges() shouldBe listOf("20261019" to "20261025", "20261026" to "20261030")
    }

    "NICE 가 실패하면 저장하지 않아 다음 요청 때 다시 부른다" {
        val (nice, _, fetcher) = setup()
        nice.failNext = true

        shouldThrow<NiceApiException.ServerException> { fetcher.periods(d(19), d(25)) }
        fetcher.periods(d(19), d(25)) shouldBe mapOf(d(19) to 6, d(20) to 6, d(21) to 6, d(22) to 6, d(23) to 6)

        nice.timetableRanges().size shouldBe 2
    }

    "갱신 주기(12시간)가 지나면 옛 값을 바로 돌려주고, 뒤에서 다시 받아 바꾼다" {
        val (nice, clock, fetcher) = setup()
        fetcher.periods(d(19), d(20))
        nice.period = 7 // 학교가 시간표를 고침

        clock.advance(Duration.ofHours(11))
        fetcher.periods(d(19), d(20)) shouldBe mapOf(d(19) to 6, d(20) to 6)
        nice.timetableRanges().size shouldBe 1 // 아직 갱신 주기 전

        clock.advance(Duration.ofHours(1))
        fetcher.periods(d(19), d(20)) shouldBe mapOf(d(19) to 6, d(20) to 6) // 기다리지 않고 옛 값
        fetcher.awaitCacheRefreshes()
        nice.timetableRanges() shouldBe listOf("20261019" to "20261020", "20261019" to "20261020")

        fetcher.periods(d(19), d(20)) shouldBe mapOf(d(19) to 7, d(20) to 7) // 갱신된 값
        nice.timetableRanges().size shouldBe 2
    }

    "아직 등록 전일 수 있는 미래 평일의 빈 시간표는 1시간마다, 내용 있는 날짜·주말은 12시간마다 다시 본다" {
        val (nice, clock, fetcher) = setup()
        nice.emptyFrom = d(26) // 다음 주(26일~) 시간표 미등록
        fetcher.periods(d(22), d(27))
        nice.emptyFrom = null // 학교가 다음 주 시간표를 등록함

        clock.advance(Duration.ofHours(1))
        fetcher.periods(d(22), d(27))
        fetcher.awaitCacheRefreshes()

        // 내용 있는 22·23일과 원래 비어 있는 주말(24·25일)은 그대로 두고, 빈 미래 평일(26·27일)만 다시 받는다
        nice.timetableRanges() shouldBe listOf("20261022" to "20261027", "20261026" to "20261027")
        fetcher.periods(d(22), d(27)) shouldBe mapOf(d(22) to 6, d(23) to 6, d(26) to 6, d(27) to 6)
    }

    "주말은 원래 시간표가 없으니, 1시간이 지나도 다시 부르지 않는다" {
        val (nice, clock, fetcher) = setup()
        fetcher.periods(d(19), d(25)) // 월~일, 토·일(24·25일)은 빈 시간표

        clock.advance(Duration.ofHours(1))
        fetcher.periods(d(19), d(25))
        fetcher.awaitCacheRefreshes()

        nice.timetableRanges().size shouldBe 1
    }

    "백그라운드 갱신이 실패하면 옛 값을 그대로 쓰고, 다음 요청 때 다시 갱신한다" {
        val (nice, clock, fetcher) = setup()
        fetcher.periods(d(19), d(19))
        clock.advance(Duration.ofHours(12))
        nice.failNext = true

        fetcher.periods(d(19), d(19)) shouldBe mapOf(d(19) to 6)
        fetcher.awaitCacheRefreshes()
        fetcher.periods(d(19), d(19)) shouldBe mapOf(d(19) to 6) // 실패 뒤에도 옛 값
        fetcher.awaitCacheRefreshes()

        nice.timetableRanges().size shouldBe 3 // 처음 + 실패한 갱신 + 다시 갱신
    }

    "학교 안 가는 날도 날짜 단위로 캐시한다 (24시간마다 갱신)" {
        val (nice, clock, fetcher) = setup()
        nice.holidays = setOf(d(23))

        fetcher.getNoSchoolDays(school, d(19), d(25)) shouldBe setOf(d(23))
        fetcher.getNoSchoolDays(school, d(23), d(23)) shouldBe setOf(d(23))
        nice.scheduleCount() shouldBe 1

        clock.advance(Duration.ofHours(23))
        fetcher.getNoSchoolDays(school, d(19), d(25))
        nice.scheduleCount() shouldBe 1

        clock.advance(Duration.ofHours(1))
        fetcher.getNoSchoolDays(school, d(19), d(25))
        fetcher.awaitCacheRefreshes()
        nice.scheduleCount() shouldBe 2
    }

    "기간 없이(학기 전체) 부르면 캐시를 거치지 않는다" {
        val (nice, _, fetcher) = setup()
        // 기간 없는 요청은 가짜 NICE 가 응답을 만들지 못해 실패하므로, 매번 NICE 까지 갔는지(요청 수)만 본다
        runCatching { fetcher.getTimetable(school, 2026, 2, 2, "1") }
        runCatching { fetcher.getTimetable(school, 2026, 2, 2, "1") }

        nice.requests.size shouldBe 2
    }
})
