package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.common.LLM_REQUEST_TIMEOUT_MILLIS
import com.github.nepyh.rooter.common.LlmThinking
import com.github.nepyh.rooter.common.LLM_RESPONSE_JSON
import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.cleanQuizLlmJson
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.requestParsedWithRetry
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
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
data class GeneratedQuestion(
    val questionText: String,
    val choices: List<String>,
    val correctIndex: Int,
    val explanation: String = "", // 풀이 과정. AI 가 빠뜨려도 파싱은 되게 기본값
    val chapterName: String = "" // 이 문제가 다루는 학습 범위 단원. 범위 밖이면 저장할 때 버린다
)

@Serializable
data class WeakAreaSuggestion(
    val chapterName: String,
    val reviewTaskDescription: String
)

/** 약점 단원 맞춤 복습 문제 하나. 복습 목록(review_questions, source=CUSTOM)에 저장된다 */
@Serializable
data class CustomReviewQuestion(
    val chapterName: String,
    val questionText: String,
    val choices: List<String>,
    val correctIndex: Int,
    val explanation: String = "",
    val choiceReasons: List<String> = emptyList() // choices 와 같은 순서, 그 보기를 골랐을 때 보여줄 틀린 이유 (정답 자리는 "")
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: LlmThinking
)

@Serializable
private data class ChatCompletionChoice(val message: ChatMessage)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>)

open class QuizLlmClient(private val appConfig: AppConfig) {

    private val json = LLM_RESPONSE_JSON
    private val logger = LoggerFactory.getLogger(QuizLlmClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = LLM_REQUEST_TIMEOUT_MILLIS
        }
    }

    open suspend fun generateQuestions(context: String, count: Int): List<GeneratedQuestion> {
        val prompt = PromptLoader.load(
            "prompts/quiz-generation.md",
            "CONTEXT" to context,
            "COUNT" to count.toString()
        )

        // 코드블록을 벗기고, 수식($...$ LaTeX)의 역슬래시를 한 번만 쓴 응답이 깨지지 않게 바로잡는다 (#388).
        // 그래도 형식이 틀리면 같은 요청을 한 번 더 보낸다 (#402)
        return requestParsedWithRetry(logger, { requestChatCompletion(prompt) }) {
            json.decodeFromString<List<GeneratedQuestion>>(cleanQuizLlmJson(it))
        } ?: throw QuizValidationException.QuizGenerationFailedException()
    }

    open suspend fun analyzeWeakAreas(wrongQuestionTexts: List<String>, chapterNames: List<String>): List<WeakAreaSuggestion> {
        val prompt = PromptLoader.load(
            "prompts/quiz-weak-area-analysis.md",
            "CHAPTER_NAMES" to chapterNames.joinToString(", "),
            "WRONG_QUESTIONS" to wrongQuestionTexts.joinToString("\n") { "- $it" }
        )

        // 코드블록을 벗기고, 수식($...$ LaTeX)의 역슬래시를 한 번만 쓴 응답이 깨지지 않게 바로잡는다 (#388).
        // 그래도 형식이 틀리면 같은 요청을 한 번 더 보낸다 (#402)
        return requestParsedWithRetry(logger, { requestChatCompletion(prompt) }) {
            json.decodeFromString<List<WeakAreaSuggestion>>(cleanQuizLlmJson(it))
        } ?: throw QuizValidationException.QuizGenerationFailedException()
    }

    open suspend fun generateCustomReviewQuestions(
        weakChapterNames: List<String>,
        wrongQuestionTexts: List<String>,
        grade: Int,
        countPerChapter: Int
    ): List<CustomReviewQuestion> {
        val prompt = PromptLoader.load(
            "prompts/review-custom-generation.md",
            "GRADE" to "중학교 ${grade}학년",
            "WEAK_CHAPTERS" to weakChapterNames.joinToString("\n") { "- $it" },
            "WRONG_QUESTIONS" to wrongQuestionTexts.joinToString("\n") { "- $it" },
            "COUNT_PER_CHAPTER" to countPerChapter.toString()
        )

        // 코드블록을 벗기고, 수식($...$ LaTeX)의 역슬래시를 한 번만 쓴 응답이 깨지지 않게 바로잡는다 (#388).
        // 그래도 형식이 틀리면 같은 요청을 한 번 더 보낸다 (#402)
        return requestParsedWithRetry(logger, { requestChatCompletion(prompt) }) {
            json.decodeFromString<List<CustomReviewQuestion>>(cleanQuizLlmJson(it))
        } ?: throw QuizValidationException.QuizGenerationFailedException()
    }

    private suspend fun requestChatCompletion(prompt: String): String {
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
            .getOrElse { throw QuizValidationException.QuizGenerationFailedException() }

        return response.choices.firstOrNull()?.message?.content
            ?: throw QuizValidationException.QuizGenerationFailedException()
    }
}
