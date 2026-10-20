package com.github.nepyh.rooter.module.planboard

import com.github.nepyh.rooter.common.LLM_REQUEST_TIMEOUT_MILLIS
import com.github.nepyh.rooter.common.LlmThinking
import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class GeneratedPlanTask(val task_name: String, val estimated_minutes: Int)

@Serializable
data class GeneratedDailyPlan(
    val day: Int,
    val topics: List<String>,
    val goal: String,
    val tasks: List<GeneratedPlanTask> = emptyList()
)

@Serializable
data class GeneratedPlan(
    val daily_plans: List<GeneratedDailyPlan>,
    val tips: List<String> = emptyList()
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>, val thinking: LlmThinking)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

open class PlanGenerationLlmClient(private val appConfig: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(PlanGenerationLlmClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = LLM_REQUEST_TIMEOUT_MILLIS
        }
    }

    open suspend fun generatePlan(context: String): GeneratedPlan {
        val prompt = PromptLoader.load("prompts/plan-generation.md", "CONTEXT" to context)

        val content = requestChatCompletion(prompt)
            ?: throw PlanBoardValidationException.GenerationFailedException()

        return runCatching { json.decodeFromString<GeneratedPlan>(content) }
            .onFailure { logger.warn("LLM 응답 JSON 파싱 실패: content={}", content.take(500), it) }
            .getOrElse { throw PlanBoardValidationException.GenerationFailedException() }
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
