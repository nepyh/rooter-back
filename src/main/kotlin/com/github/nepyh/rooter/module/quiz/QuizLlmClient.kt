package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
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
data class GeneratedQuestion(
    val questionText: String,
    val choices: List<String>,
    val correctIndex: Int
)

@Serializable
data class WeakAreaSuggestion(
    val chapterName: String,
    val reviewTaskDescription: String
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>
)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

class QuizLlmClient(private val appConfig: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun generateQuestions(context: String, count: Int): List<GeneratedQuestion> {
        val prompt = PromptLoader.load(
            "prompts/quiz-generation.md",
            "CONTEXT" to context,
            "COUNT" to count.toString()
        )

        val content = requestChatCompletion(prompt)
        return runCatching { json.decodeFromString<List<GeneratedQuestion>>(content) }
            .getOrElse { throw QuizValidationException.QuizGenerationFailedException() }
    }

    suspend fun analyzeWeakAreas(wrongQuestionTexts: List<String>, chapterNames: List<String>): List<WeakAreaSuggestion> {
        val prompt = PromptLoader.load(
            "prompts/quiz-weak-area-analysis.md",
            "CHAPTER_NAMES" to chapterNames.joinToString(", "),
            "WRONG_QUESTIONS" to wrongQuestionTexts.joinToString("\n") { "- $it" }
        )

        val content = requestChatCompletion(prompt)
        return runCatching { json.decodeFromString<List<WeakAreaSuggestion>>(content) }
            .getOrElse { throw QuizValidationException.QuizGenerationFailedException() }
    }

    private suspend fun requestChatCompletion(prompt: String): String {
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
        }.getOrElse { throw QuizValidationException.QuizGenerationFailedException() }

        return response.choices.firstOrNull()?.message?.content
            ?: throw QuizValidationException.QuizGenerationFailedException()
    }
}
