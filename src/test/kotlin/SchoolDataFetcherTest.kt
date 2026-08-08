package com.github.nepyh.rooter

import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.school.exception.NiceApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SchoolDataFetcherTest : StringSpec({

    fun fetcherWith(handler: MockRequestHandler): SchoolDataFetcher {
        val engine = MockEngine(handler)
        val client = NiceApiClient(apiKey = "test-key", httpClient = HttpClient(engine))
        return SchoolDataFetcher(client)
    }

    suspend fun MockRequestHandleScope.jsonResponse(body: String): HttpResponseData = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json")
    )

    "학교 검색은 NICE schoolInfo row 를 School 도메인 모델로 변환한다" {
        val fetcher = fetcherWith { request ->
            request.url.parameters["SCHUL_NM"] shouldBe "부산"
            request.url.parameters["SCHUL_KND_SC_NM"] shouldBe "중학교"
            jsonResponse(
                """
                {"schoolInfo":[{"head":[{"list_total_count":1},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[{"ATPT_OFCDC_SC_CODE":"C10","ATPT_OFCDC_SC_NM":"부산광역시교육청","SD_SCHUL_CODE":"7181084","SCHUL_NM":"부산개성중학교","SCHUL_KND_SC_NM":"중학교","LCTN_SC_NM":"부산광역시","FOND_SC_NM":"공립"}]}]}
                """.trimIndent()
            )
        }

        val schools = fetcher.searchSchools("부산")

        schools.size shouldBe 1
        schools[0].schoolId shouldBe "C107181084"
        schools[0].officeCode shouldBe "C10"
        schools[0].schoolCode shouldBe "7181084"
        schools[0].name shouldBe "부산개성중학교"
        schools[0].kind shouldBe "중학교"
        schools[0].region shouldBe "부산광역시"
        schools[0].foundation shouldBe "공립"
    }

    "학교 검색 결과가 없으면(INFO-200) 빈 목록을 반환한다" {
        val fetcher = fetcherWith {
            jsonResponse("""{"schoolInfo":[{"head":[{"list_total_count":0},{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}]},{"row":[]}]}""")
        }

        fetcher.searchSchools("없는학교").shouldBeEmpty()
    }

    "인증키 오류(INFO-100) 는 InvalidKeyException 으로 변환한다" {
        val fetcher = fetcherWith {
            jsonResponse("""{"schoolInfo":[{"head":[{"RESULT":{"CODE":"INFO-100","MESSAGE":"인증키가 유효하지 않습니다."}}]},{"row":[]}]}""")
        }

        val ex = shouldThrow<NiceApiException.InvalidKeyException> { fetcher.searchSchools("부산") }
        ex.message shouldBe "인증키가 유효하지 않습니다."
    }

    "시간표는 반을 지정하지 않으면 CLASS_NM 파라미터 없이 학년 전체를 조회한다" {
        var classParam: String? = "sentinel"
        val fetcher = fetcherWith { request ->
            classParam = request.url.parameters["CLASS_NM"]
            jsonResponse(
                """
                {"misTimetable":[{"head":[{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[{"ALL_TI_YMD":"20260309","PERIO":"1","ITRT_CNTNT":"체육","CLASS_NM":"1"},{"ALL_TI_YMD":"20260309","PERIO":"2","ITRT_CNTNT":"영어","CLASS_NM":"2"}]}]}
                """.trimIndent()
            )
        }

        val timetable = fetcher.getTimetable("C107181084", 2026, 1, 1)

        classParam shouldBe null
        timetable.size shouldBe 2
        timetable[0].date shouldBe "20260309"
        timetable[0].period shouldBe 1
        timetable[0].subject shouldBe "체육"
        timetable[1].className shouldBe "2"
    }

    "시간표는 반을 지정하면 CLASS_NM 파라미터를 포함해 요청한다" {
        var classParam: String? = null
        val fetcher = fetcherWith { request ->
            classParam = request.url.parameters["CLASS_NM"]
            jsonResponse("""{"misTimetable":[{"head":[{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[{"ALL_TI_YMD":"20260309","PERIO":"1","ITRT_CNTNT":"체육","CLASS_NM":"1"}]}]}""")
        }

        fetcher.getTimetable("C107181084", 2026, 1, 1, className = "3")

        classParam shouldBe "3"
    }

    "학사일정은 SchoolSchedule row 를 SchoolEvent 로 변환한다" {
        val fetcher = fetcherWith {
            jsonResponse("""{"SchoolSchedule":[{"head":[{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[{"AA_YMD":"20250301","EVENT_NM":"3·1절"},{"AA_YMD":"20250303","EVENT_NM":"대체공휴일"}]}]}""")
        }

        val events = fetcher.getSchoolEvents("C107181084", 2026)

        events.size shouldBe 2
        events[0].date shouldBe "20250301"
        events[0].name shouldBe "3·1절"
    }

    "반 목록 조회는 classInfo 의 CLASS_NM 을 수집한다" {
        val fetcher = fetcherWith {
            jsonResponse("""{"classInfo":[{"head":[{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},{"row":[{"CLASS_NM":"1"},{"CLASS_NM":"2"},{"CLASS_NM":"3"}]}]}""")
        }

        val classes = fetcher.getClasses("C107181084", 2026, 1, 1)

        classes shouldBe listOf("1", "2", "3")
    }

    "잘못된 schoolId 형식은 HTTP 호출 전에 BadRequestException 을 던진다" {
        var called = false
        val fetcher = fetcherWith {
            called = true
            jsonResponse("{}")
        }

        shouldThrow<NiceApiException.BadRequestException> {
            fetcher.getTimetable("B10", 2026, 1, 1)
        }
        called shouldBe false
    }
})
