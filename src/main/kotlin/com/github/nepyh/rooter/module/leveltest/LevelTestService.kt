package com.github.nepyh.rooter.module.leveltest

import com.github.nepyh.rooter.module.leveltest.dto.LevelTestAnswer
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestGenerateResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestQuestionPublicResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestQuestionResultResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestSubjectScoreResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestSubmitResponse
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestNotFoundException
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestValidationException
import com.github.nepyh.rooter.module.leveltest.model.LevelTestAttemptRow
import com.github.nepyh.rooter.module.leveltest.model.LevelTestAttemptTable
import com.github.nepyh.rooter.module.leveltest.model.LevelTestChoiceRow
import com.github.nepyh.rooter.module.leveltest.model.LevelTestChoiceTable
import com.github.nepyh.rooter.module.leveltest.model.LevelTestQuestionRow
import com.github.nepyh.rooter.module.leveltest.model.LevelTestQuestionTable
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultRow
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime
import kotlin.math.roundToInt

class LevelTestService(
    private val llmClient: LevelTestLlmClient
) {

    suspend fun generateTest(userId: Int, grade: Int): LevelTestGenerateResponse {
        if (grade !in 1..3) throw LevelTestValidationException.InvalidGradeException()

        val referenceGradeLabel = referenceGradeLabelFor(grade)
        val generated = llmClient.generateQuestions(referenceGradeLabel)

        return newSuspendedTransaction {
            val attempt = LevelTestAttemptRow.new {
                user = UserRow[userId]
                this.grade = grade
                this.referenceGradeLabel = referenceGradeLabel
                createdAt = OffsetDateTime.now()
            }

            val questions = generated.map { question ->
                val subject = findOrCreateSubject(question.subject)

                val levelTestQuestion = LevelTestQuestionRow.new {
                    this.attempt = attempt
                    this.subject = subject
                    questionText = question.question_text
                }

                question.choices.forEachIndexed { index, choiceText ->
                    LevelTestChoiceRow.new {
                        this.question = levelTestQuestion
                        this.choiceText = choiceText
                        isCorrect = index == question.correct_index
                        explanation = question.explanation
                    }
                }

                LevelTestQuestionPublicResponse(
                    id = levelTestQuestion.id.value,
                    subject = question.subject,
                    questionText = question.question_text,
                    choices = question.choices
                )
            }

            LevelTestGenerateResponse(
                attemptId = attempt.id.value,
                referenceGradeLabel = referenceGradeLabel,
                questions = questions
            )
        }
    }

    suspend fun submitTest(userId: Int, attemptId: Int, answers: List<LevelTestAnswer>): LevelTestSubmitResponse =
        newSuspendedTransaction {
            val attempt = LevelTestAttemptRow.find {
                (LevelTestAttemptTable.id eq attemptId) and (LevelTestAttemptTable.userId eq userId)
            }.firstOrNull() ?: throw LevelTestNotFoundException()

            if (attempt.isSubmitted) {
                throw LevelTestValidationException.AlreadySubmittedException()
            }

            val questionRows = LevelTestQuestionRow.find { LevelTestQuestionTable.attemptId eq attemptId }
                .associateBy { it.id.value }
            if (questionRows.isEmpty()) throw LevelTestNotFoundException()

            val questionIds = questionRows.keys
            if (answers.any { it.questionId !in questionIds }) {
                throw LevelTestValidationException.InvalidAnswerException()
            }

            val choicesByQuestion = LevelTestChoiceRow.find { LevelTestChoiceTable.questionId inList questionIds }
                .groupBy { it.question.id.value }
                .mapValues { (_, rows) -> rows.sortedBy { it.id.value } }

            val results = answers.map { answer ->
                val questionRow = questionRows.getValue(answer.questionId)
                val choices = choicesByQuestion[answer.questionId].orEmpty()
                if (answer.selectedIndex !in choices.indices) {
                    throw LevelTestValidationException.InvalidAnswerException()
                }
                val correctIndex = choices.indexOfFirst { it.isCorrect }

                LevelTestQuestionResultResponse(
                    questionId = answer.questionId,
                    subject = questionRow.subject.name,
                    isCorrect = answer.selectedIndex == correctIndex,
                    selectedIndex = answer.selectedIndex,
                    correctIndex = correctIndex,
                    explanation = choices[correctIndex].explanation
                )
            }

            val subjectScores = results.groupBy { it.subject }.map { (subjectName, subjectResults) ->
                val correctCount = subjectResults.count { it.isCorrect }
                val totalCount = subjectResults.size
                val subject = SubjectRow.find { SubjectTable.name eq subjectName }.first()

                LevelTestResultRow.new {
                    user = UserRow[userId]
                    this.subject = subject
                    // score는 0~100 정답률(%)로 저장한다. 문항 수가 시도마다 다를 수 있어 원시 정답
                    // 개수만 저장하면 나중에 총 문항 수 없이는 등급을 다시 계산할 수 없기 때문.
                    score = ((correctCount.toDouble() / totalCount) * 100).roundToInt()
                    createdAt = OffsetDateTime.now()
                }

                LevelTestSubjectScoreResponse(
                    subject = subjectName,
                    correctCount = correctCount,
                    totalCount = totalCount,
                    tier = computeTier(correctCount, totalCount)
                )
            }

            attempt.isSubmitted = true

            val correctCount = results.count { it.isCorrect }
            val totalCount = results.size

            LevelTestSubmitResponse(
                correctCount = correctCount,
                totalCount = totalCount,
                tier = computeTier(correctCount, totalCount),
                subjectScores = subjectScores,
                results = results
            )
        }

    private fun findOrCreateSubject(name: String): SubjectRow =
        SubjectRow.find { SubjectTable.name eq name }.firstOrNull()
            ?: SubjectRow.new { this.name = name }

    /** 실력 테스트는 현재 학년보다 한 단계 아래 수준으로 출제한다 (중1은 초6 수준까지 내려간다). */
    private fun referenceGradeLabelFor(grade: Int): String = when (grade) {
        1 -> "초등학교 6학년"
        2 -> "중학교 1학년"
        3 -> "중학교 2학년"
        else -> "중학교 ${grade - 1}학년"
    }

    /** 정답률로 등급을 매긴다: 80% 이상 상, 40~79% 중, 그 미만 하. 문항이 0개면 안전하게 "중". */
    private fun computeTier(correctCount: Int, totalCount: Int): String {
        if (totalCount <= 0) return "중"
        val rate = correctCount.toDouble() / totalCount
        return when {
            rate >= 0.8 -> "상"
            rate >= 0.4 -> "중"
            else -> "하"
        }
    }
}
