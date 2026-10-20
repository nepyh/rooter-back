package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.common.LLM_REQUEST_TIMEOUT_MILLIS
import com.github.nepyh.rooter.common.LlmThinking
import com.github.nepyh.rooter.common.PromptLoader
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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

    private val json = Json { ignoreUnknownKeys = true }

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

        val content = requestChatCompletion(prompt)
        return runCatching { json.decodeFromString<List<GeneratedQuestion>>(content) }
            .getOrElse { throw QuizValidationException.QuizGenerationFailedException() }
    }

    open suspend fun analyzeWeakAreas(wrongQuestionTexts: List<String>, chapterNames: List<String>): List<WeakAreaSuggestion> {
        val prompt = PromptLoader.load(
            "prompts/quiz-weak-area-analysis.md",
            "CHAPTER_NAMES" to chapterNames.joinToString(", "),
            "WRONG_QUESTIONS" to wrongQuestionTexts.joinToString("\n") { "- $it" }
        )

        val content = requestChatCompletion(prompt)
        return runCatching { json.decodeFromString<List<WeakAreaSuggestion>>(content) }
            .getOrElse { throw QuizValidationException.QuizGenerationFailedException() }
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

        val content = requestChatCompletion(prompt)
        return runCatching { json.decodeFromString<List<CustomReviewQuestion>>(content) }
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
                        messages = listOf(ChatMessage(role = "user", content = prompt)),
                        thinking = LlmThinking.DISABLED
                    )
                )
            }.body<ChatCompletionResponse>()
        }.getOrElse { throw QuizValidationException.QuizGenerationFailedException() }

        return response.choices.firstOrNull()?.message?.content
            ?: throw QuizValidationException.QuizGenerationFailedException()
    }
}
