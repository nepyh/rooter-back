package com.github.nepyh.rooter.module.leveltest

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
        val prompt = """
            학생의 실제 학년보다 한 단계 아래인 "$referenceGradeLabel" 수준의 국어/영어/수학 핵심 기초를
            확인하는 배치고사 문제를 통합 5문항 내외로 만들어라.
            - 각 문제는 국어/영어/수학 중 하나의 과목에 속한다.
            - $referenceGradeLabel 교육과정 범위를 벗어나는(그보다 높은 학년) 개념은 절대 묻지 마라.
            - 각 문제는 보기 4개를 가지며, 그 중 정답은 하나다.
            - explanation에는 정답인 이유를 한두 문장으로 간단히 설명해라.
            반드시 아래 JSON 형식으로만 응답하고, 다른 설명은 절대 붙이지 마라.
            {"questions": [{"subject": "국어", "question_text": "...", "choices": ["...", "...", "...", "..."], "correct_index": 0, "explanation": "..."}]}
        """.trimIndent()

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
