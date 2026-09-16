package com.github.nepyh.rooter.module.taskquiz

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
        val prompt = """
            학생이 방금 다음 학습을 끝냈다: "$taskName"
            이 내용을 실제로 이해했는지 확인하는 객관식 퀴즈 ${LLM_QUESTION_COUNT}문항을 만들어라.
            - 반드시 "$taskName"의 범위 안에서만 출제하고, 다른 내용을 새로 지어내지 마라.
            - 각 문제는 보기 4개를 가지며, 그 중 정답은 하나다.
            - explanation에는 정답인 이유를 한두 문장으로 간단히 설명해라.
            반드시 아래 JSON 형식으로만 응답하고, 다른 설명은 절대 붙이지 마라.
            {"questions": [{"question_text": "...", "choices": ["...", "...", "...", "..."], "correct_index": 0, "explanation": "..."}]}
        """.trimIndent()

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
