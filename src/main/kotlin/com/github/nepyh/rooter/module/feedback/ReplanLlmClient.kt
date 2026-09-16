package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.common.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReplanSuggestion(
    val dayOffset: Int, // 오늘(피드백 제출한 날) 기준 며칠 뒤에 배치할지 (1 이상)
    val taskName: String,
    val estimatedMinutes: Int
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

class ReplanLlmClient(private val appConfig: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun suggestAdjustments(context: String): List<ReplanSuggestion> {
        val prompt = """
            다음은 학생이 오늘 제출한 학습 피드백과 관련 정보다.
            $context

            이 정보를 바탕으로, 남은 학습 일정에 추가하면 좋을 보충/심화 학습 태스크를 0~3개 제안해라.
            - 체감 난이도가 어렵거나 집중도가 낮았다면 기초를 다지는 복습 태스크를 제안해라.
            - 예상보다 시간이 오래 걸렸다면 부담을 줄이는 방향으로 적은 개수만 제안하거나 제안하지 않아도 된다.
            - 난이도가 쉬웠고 집중도가 높았다면 심화 학습 태스크를 제안해도 된다.
            - dayOffset은 1 이상의 정수(오늘로부터 며칠 뒤)여야 한다.
            반드시 아래 JSON 배열 형식으로만 응답하고, 다른 설명은 절대 붙이지 마라. 제안할 게 없으면 빈 배열 []을 반환해라.
            [{"dayOffset": 1, "taskName": "...", "estimatedMinutes": 20}]
        """.trimIndent()

        val content = requestChatCompletion(prompt) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ReplanSuggestion>>(content) }
            .getOrElse { emptyList() }
    }

    private suspend fun requestChatCompletion(prompt: String): String? {
        val response = runCatching {
            client.post("${appConfig.llmBaseUrl}/chat/completions") {
                header("Authorization", "Bearer ${appConfig.llmApiKey}")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatCompletionRequest(
                        model = appConfig.llmModel,
                        messages = listOf(ChatMessage(role = "user", content = prompt))
                    )
                )
            }.body<ChatCompletionResponse>()
        }.getOrNull() ?: return null

        return response.choices.firstOrNull()?.message?.content
    }
}
