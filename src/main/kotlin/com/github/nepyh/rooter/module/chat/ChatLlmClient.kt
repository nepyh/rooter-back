package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.chat.dto.AiChatResult
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
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

class ChatLlmClient(private val appConfig: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun adjustPlan(
        grade: Int,
        studyStyleSummary: String,
        targetDate: String,
        currentTasksJson: String,
        chatHistoryJson: String,
        userMessage: String
    ): AiChatResult? {
        val prompt = PromptLoader.load(
            "prompts/chat-plan-adjustment.md",
            "GRADE" to grade.toString(),
            "STUDY_STYLE_SUMMARY" to studyStyleSummary,
            "TARGET_DATE" to targetDate,
            "CURRENT_TASKS_JSON" to currentTasksJson,
            "CHAT_HISTORY_JSON" to chatHistoryJson,
            "USER_MESSAGE" to userMessage
        )

        val content = requestChatCompletion(prompt) ?: return null
        return runCatching { json.decodeFromString<AiChatResult>(content) }.getOrNull()
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
