package com.github.nepyh.rooter.module.quiz

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
        val prompt = """
            다음은 학생이 오늘 학습한 범위와 완료한 학습 내용이다.
            $context

            이 내용을 바탕으로 객관식 문제 ${count}개를 만들어라.
            각 문제는 보기 4개를 가지며, 그 중 정답은 하나다.
            반드시 아래 JSON 배열 형식으로만 응답하고, 다른 설명은 절대 붙이지 마라.
            [{"questionText": "...", "choices": ["...", "...", "...", "..."], "correctIndex": 0}]
        """.trimIndent()

        val content = requestChatCompletion(prompt)
        return runCatching { json.decodeFromString<List<GeneratedQuestion>>(content) }
            .getOrElse { throw QuizValidationException.QuizGenerationFailedException() }
    }

    suspend fun analyzeWeakAreas(wrongQuestionTexts: List<String>, chapterNames: List<String>): List<WeakAreaSuggestion> {
        val prompt = """
            학생이 다음 챕터 범위를 학습했다: ${chapterNames.joinToString(", ")}
            그런데 아래 문제들을 틀렸다:
            ${wrongQuestionTexts.joinToString("\n") { "- $it" }}

            위 오답들을 바탕으로 학생이 취약한 챕터를 판단하고, 챕터마다 짧은 복습 태스크 설명을 하나씩 제안해라.
            반드시 아래 JSON 배열 형식으로만 응답하고, 다른 설명은 절대 붙이지 마라.
            [{"chapterName": "...", "reviewTaskDescription": "..."}]
        """.trimIndent()

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
