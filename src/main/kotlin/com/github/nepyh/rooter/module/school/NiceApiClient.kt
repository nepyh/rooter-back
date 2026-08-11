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
 * - 응답: {"<서비스명>": [{"head": [...]}, {"row": [...]}]} 형태
 * - RESULT 코드: INFO-000 정상 / INFO-100 인증키 오류 / INFO-200 데이터 없음 / INFO-300 요청 제한 / INFO-400 파라미터 오류 / INFO-500 서버 오류
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
    suspend fun <T> getRows(service: String, params: Map<String, String>, serializer: KSerializer<T>): List<T> {
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
            ?: throw NiceApiException.UnexpectedResponseException("응답에 '$service' 블록이 없습니다.")

        var resultCode: String? = null
        var resultMessage: String? = null
        var rows: List<JsonObject> = emptyList()

        for (block in serviceBlock) {
            val blockObj = block.jsonObject
            blockObj["head"]?.jsonArray?.forEach { head ->
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
            "INFO-200" -> return emptyList() // 데이터 없음 = 빈 목록 (정상)
            "INFO-100" -> throw NiceApiException.InvalidKeyException(resultMessage)
            "INFO-300" -> throw NiceApiException.RateLimitedException(resultMessage)
            "INFO-400" -> throw NiceApiException.BadRequestException(resultMessage)
            "INFO-500" -> throw NiceApiException.ServerException(resultMessage)
            else -> throw NiceApiException.UnexpectedResponseException("알 수 없는 RESULT 코드: $resultCode")
        }

        return rows.map { json.decodeFromJsonElement(serializer, it) }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://open.neis.go.kr/hub"
        const val MAX_PAGE_SIZE = "100"

        val json = Json { ignoreUnknownKeys = true }

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }
}

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
