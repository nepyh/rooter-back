package com.github.nepyh.rooter.module.school

import com.github.nepyh.rooter.module.school.exception.NiceApiException
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * 나이스 교육정보 개방포털(NICE) Open API 저수준 클라이언트.
 *
 * - 베이스: https://open.neis.go.kr/hub/{서비스명}
 * - 인증: KEY 파라미터 (키가 없으면 응답이 5건으로 제한됨)
 * - 응답: {"<서비스명>": [{"head": [...]}, {"row": [...]}]} 형태.
 *   단, 데이터 없음(INFO-200)과 오류(ERROR-xxx)는 서비스 블록 없이 최상위 {"RESULT": {...}} 만 온다.
 * - RESULT 코드: INFO-000 정상 / INFO-200 데이터 없음 / INFO-300 인증키 사용 제한 /
 *   ERROR-290 인증키 오류 / ERROR-300·333·336 요청 인자 오류 / ERROR-337 일별 트래픽 초과 / ERROR-500·600·601 서버 오류
 *   (INFO-200 은 빈 목록으로 처리 — 팀 API 컨벤션 "조회 결과 없음 = 빈 배열")
 *
 * 모든 메서드는 suspend (Ktor HttpClient 비동기 IO).
 */
class NiceApiClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient = defaultHttpClient()
) {

    /**
     * NICE 서비스를 호출하고 row 목록을 [T] 로 디코딩해 반환한다.
     * @param service NICE 서비스명 (schoolInfo, misTimetable, SchoolSchedule, classInfo)
     * @param params 서비스별 파라미터 (pSize 는 기본 100, params 로 오버라이드 가능)
     * @param serializer row DTO 의 kotlinx.serialization 시리얼라이저
     */
    suspend fun <T> getRows(service: String, params: Map<String, String>, serializer: KSerializer<T>): List<T> =
        fetchPage(service, params, serializer).rows

    /**
     * [getRows] 와 같지만 list_total_count 를 보고 pIndex 를 넘겨가며 모든 페이지를 모아 반환한다.
     * 한 페이지를 넘을 수 있는 조회(예: 1년치 학사일정, 시간표)에 쓴다. 호출 수를 줄이려고 NICE 최대치인 1000건씩 요청하고,
     * 호출 수 폭주를 막기 위해 [maxPages] 에서 멈춘다. (params 에 pSize 를 주면 그 값을 쓴다)
     */
    suspend fun <T> getAllRows(
        service: String,
        params: Map<String, String>,
        serializer: KSerializer<T>,
        maxPages: Int = DEFAULT_MAX_PAGES
    ): List<T> {
        val pagedParams = mapOf("pSize" to ALL_ROWS_PAGE_SIZE) + params
        val first = fetchPage(service, pagedParams, serializer)
        val rows = first.rows.toMutableList()
        var pageIndex = 1
        while (rows.size < first.totalCount && pageIndex < maxPages) {
            pageIndex++
            val page = fetchPage(service, pagedParams + ("pIndex" to pageIndex.toString()), serializer)
            if (page.rows.isEmpty()) break
            rows += page.rows
        }
        return rows
    }

    private suspend fun <T> fetchPage(service: String, params: Map<String, String>, serializer: KSerializer<T>): NicePage<T> {
        val allParams = buildMap {
            put("KEY", apiKey)
            put("Type", "json")
            put("pSize", MAX_PAGE_SIZE)
            putAll(params)
        }

        val response = httpClient.get("$baseUrl/$service") {
            allParams.forEach { (key, value) -> parameter(key, value) }
        }

        if (!response.status.isSuccess()) {
            throw NiceApiException.ServerException("NICE HTTP ${response.status.value} 오류")
        }

        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val serviceBlock = root[service]?.jsonArray

        var resultCode: String? = null
        var resultMessage: String? = null
        var totalCount = 0
        var rows: List<JsonObject> = emptyList()

        if (serviceBlock == null) {
            // 데이터 없음·오류 응답은 서비스 블록 없이 최상위 RESULT 만 온다
            val result = root["RESULT"]?.jsonObject
                ?: throw NiceApiException.UnexpectedResponseException("응답에 '$service' 블록이 없습니다.")
            resultCode = result["CODE"]?.jsonPrimitive?.content
            resultMessage = result["MESSAGE"]?.jsonPrimitive?.contentOrNull
        }

        for (block in serviceBlock.orEmpty()) {
            val blockObj = block.jsonObject
            blockObj["head"]?.jsonArray?.forEach { head ->
                head.jsonObject["list_total_count"]?.jsonPrimitive?.intOrNull?.let { totalCount = it }
                val result = head.jsonObject["RESULT"] ?: return@forEach
                resultCode = result.jsonObject["CODE"]?.jsonPrimitive?.content
                resultMessage = result.jsonObject["MESSAGE"]?.jsonPrimitive?.contentOrNull
            }
            blockObj["row"]?.let { rowArray ->
                rows = rowArray.jsonArray.map { it.jsonObject }
            }
        }

        when (resultCode) {
            null -> throw NiceApiException.UnexpectedResponseException("응답에 RESULT 블록이 없습니다.")
            "INFO-000" -> Unit
            "INFO-200" -> return NicePage(emptyList(), 0) // 데이터 없음 = 빈 목록 (정상)
            "INFO-100", "ERROR-290" -> throw NiceApiException.InvalidKeyException(resultMessage)
            "INFO-300", "ERROR-337" -> throw NiceApiException.RateLimitedException(resultMessage)
            "INFO-400", "ERROR-300", "ERROR-333", "ERROR-336" -> throw NiceApiException.BadRequestException(resultMessage)
            "INFO-500", "ERROR-500", "ERROR-600", "ERROR-601" -> throw NiceApiException.ServerException(resultMessage)
            else -> throw NiceApiException.UnexpectedResponseException("알 수 없는 RESULT 코드: $resultCode")
        }

        return NicePage(rows.map { json.decodeFromJsonElement(serializer, it) }, totalCount)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://open.neis.go.kr/hub"
        const val MAX_PAGE_SIZE = "100"
        const val ALL_ROWS_PAGE_SIZE = "1000" // NICE 한 번 요청 최대치 (넘으면 ERROR-336)
        const val DEFAULT_MAX_PAGES = 10

        val json = Json { ignoreUnknownKeys = true }

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }
}

private data class NicePage<T>(val rows: List<T>, val totalCount: Int)

// ---- NICE 응답 row DTO (와이어 포맷 — 필드명은 NICE 원본 대문자 스네이크) ----

@Serializable
data class SchoolRow(
    @SerialName("ATPT_OFCDC_SC_CODE") val officeCode: String = "",
    @SerialName("ATPT_OFCDC_SC_NM") val officeName: String = "",
    @SerialName("SD_SCHUL_CODE") val schoolCode: String = "",
    @SerialName("SCHUL_NM") val name: String = "",
    @SerialName("SCHUL_KND_SC_NM") val kind: String = "",
    @SerialName("LCTN_SC_NM") val region: String = "",
    @SerialName("FOND_SC_NM") val foundation: String? = null
)

@Serializable
data class TimetableRow(
    @SerialName("ALL_TI_YMD") val date: String = "",
    @SerialName("PERIO") val period: String = "",
    @SerialName("ITRT_CNTNT") val subject: String = "",
    @SerialName("CLASS_NM") val className: String = ""
)

@Serializable
data class SchoolEventRow(
    @SerialName("AA_YMD") val date: String = "",
    @SerialName("EVENT_NM") val name: String = ""
)

@Serializable
data class ClassInfoRow(
    @SerialName("CLASS_NM") val className: String = ""
)
