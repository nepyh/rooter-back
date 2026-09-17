package com.github.nepyh.rooter.module.leveltest

import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestValidationException
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
data class GeneratedLevelTestQuestion(
    val subject: String, // "국어" | "영어" | "수학"
    val question_text: String,
    val choices: List<String>,
    val correct_index: Int,
    val explanation: String
)

@Serializable
private data class GeneratedLevelTest(val questions: List<GeneratedLevelTestQuestion>)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

class LevelTestLlmClient(private val appConfig: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun generateQuestions(referenceGradeLabel: String): List<GeneratedLevelTestQuestion> {
        val prompt = PromptLoader.load(
            "prompts/level-test-generation.md",
            "REFERENCE_GRADE_LABEL" to referenceGradeLabel
        )

        val content = requestChatCompletion(prompt)
            ?: throw LevelTestValidationException.TestGenerationFailedException()

        return runCatching { json.decodeFromString<GeneratedLevelTest>(content).questions }
            .getOrElse { throw LevelTestValidationException.TestGenerationFailedException() }
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
