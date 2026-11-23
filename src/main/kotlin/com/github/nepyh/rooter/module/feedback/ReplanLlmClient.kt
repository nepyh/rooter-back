package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.common.LLM_REQUEST_TIMEOUT_MILLIS
import com.github.nepyh.rooter.common.LLM_RESPONSE_JSON
import com.github.nepyh.rooter.common.LlmThinking
import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.stripCodeFence
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ReplanSuggestion(
    val dayOffset: Int, // 오늘(피드백 제출한 날) 기준 며칠 뒤에 배치할지 (1 이상)
    val taskName: String,
    val estimatedMinutes: Int
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>, val thinking: LlmThinking)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

class ReplanLlmClient(private val appConfig: AppConfig) {

    private val json = LLM_RESPONSE_JSON
    private val logger = LoggerFactory.getLogger(ReplanLlmClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = LLM_REQUEST_TIMEOUT_MILLIS
        }
    }

    /**
     * AI 가 제안한 보충 할일. 제안이 없으면(빈 배열) 빈 목록이고, 호출·응답이 실패하면 [ReplanLlmException] 을 던진다.
     * 예전엔 실패도 빈 목록으로 돌려줘 자정 배치가 done 으로 끝나고 다시 하지 않았다 (#318)
     */
    suspend fun suggestAdjustments(context: String): List<ReplanSuggestion> {
        val prompt = PromptLoader.load("prompts/feedback-replan.md", "CONTEXT" to context)

        val content = requestChatCompletion(prompt) ?: throw ReplanLlmException("LLM 호출 실패 또는 빈 응답")
        // 코드블록·배열 끝 쉼표 때문에 실패(→ 재시도로 AI 를 다시 부름)가 되지 않게 벗기고 너그럽게 읽는다
        return runCatching { json.decodeFromString<List<ReplanSuggestion>>(stripCodeFence(content)) }
            .onFailure { logger.warn("LLM 응답 JSON 파싱 실패: content={}", content.take(500), it) }
            .getOrElse { throw ReplanLlmException("LLM 응답 JSON 파싱 실패", it) }
    }

    private suspend fun requestChatCompletion(prompt: String): String? {
        val response = runCatching {
            val httpResponse = client.post("${appConfig.llmBaseUrl}/chat/completions") {
                header("Authorization", "Bearer ${appConfig.llmApiKey}")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatCompletionRequest(
                        model = appConfig.llmModel,
                        messages = listOf(ChatMessage(role = "user", content = prompt)),
                        thinking = LlmThinking.DISABLED
                    )
                )
            }
            if (!httpResponse.status.isSuccess()) {
                error("LLM 응답 상태 ${httpResponse.status}: ${httpResponse.bodyAsText().take(500)}")
            }
            httpResponse.body<ChatCompletionResponse>()
        }.onFailure { logger.warn("LLM 호출 실패: model={}", appConfig.llmModel, it) }
            .getOrNull() ?: return null

        return response.choices.firstOrNull()?.message?.content
    }
}

/** 자정 재조정의 AI 호출·응답 실패. 스케줄러가 job_runs 에 failed 로 남기고 재시도한다 (#318) */
class ReplanLlmException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
