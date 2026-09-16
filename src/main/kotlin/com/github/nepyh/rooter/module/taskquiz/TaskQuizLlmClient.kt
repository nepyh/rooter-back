package com.github.nepyh.rooter.module.taskquiz

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

private const val LLM_QUESTION_COUNT = 5

@Serializable
data class GeneratedTaskQuizQuestion(
    val question_text: String,
    val choices: List<String>,
    val correct_index: Int,
    val explanation: String
)

@Serializable
private data class GeneratedTaskQuiz(val questions: List<GeneratedTaskQuizQuestion>)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

class TaskQuizLlmClient(private val appConfig: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    /** taskName(예: "인수분해를 이용한 이차방정식 풀이") 하나만 다루는 객관식 5문항을 만든다. */
    suspend fun generateQuestions(taskName: String): List<GeneratedTaskQuizQuestion> {
        val prompt = PromptLoader.load(
            "prompts/task-quiz-generation.md",
            "TASK_NAME" to taskName,
            "COUNT" to LLM_QUESTION_COUNT.toString()
        )

        val content = requestChatCompletion(prompt) ?: return emptyList()
        return runCatching { json.decodeFromString<GeneratedTaskQuiz>(content).questions }
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
