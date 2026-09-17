package com.github.nepyh.rooter.module.feedback

import com.github.nepyh.rooter.common.PromptLoader
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
        val prompt = PromptLoader.load("prompts/feedback-replan.md", "CONTEXT" to context)

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
