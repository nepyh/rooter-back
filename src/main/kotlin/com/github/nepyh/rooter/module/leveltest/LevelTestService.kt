package com.github.nepyh.rooter.module.leveltest

import com.github.nepyh.rooter.module.leveltest.dto.LevelTestAnswer
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestGenerateResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestQuestionPublicResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestQuestionResultResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestSubjectScoreResponse
import com.github.nepyh.rooter.module.leveltest.dto.LevelTestSubmitResponse
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestNotFoundException
import com.github.nepyh.rooter.module.leveltest.exception.LevelTestValidationException
import com.github.nepyh.rooter.module.leveltest.model.LevelTestAttempts
import com.github.nepyh.rooter.module.leveltest.model.LevelTestChoices
import com.github.nepyh.rooter.module.leveltest.model.LevelTestQuestions
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResults
import com.github.nepyh.rooter.module.planboard.model.Subjects
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
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
            val attemptId = LevelTestAttempts.insert {
                it[this.userId] = userId
                it[this.grade] = grade
                it[this.referenceGradeLabel] = referenceGradeLabel
                it[createdAt] = OffsetDateTime.now()
            } get LevelTestAttempts.id

            val questions = generated.map { question ->
                val subjectId = findOrCreateSubject(question.subject)

                val questionId = LevelTestQuestions.insert {
                    it[this.attemptId] = attemptId.value
                    it[this.subjectId] = subjectId
                    it[questionText] = question.question_text
                } get LevelTestQuestions.id

                question.choices.forEachIndexed { index, choiceText ->
                    LevelTestChoices.insert {
                        it[this.questionId] = questionId
                        it[this.choiceText] = choiceText
                        it[isCorrect] = index == question.correct_index
                        it[explanation] = question.explanation
                    }
                }

                LevelTestQuestionPublicResponse(
                    id = questionId,
                    subject = question.subject,
                    questionText = question.question_text,
                    choices = question.choices
                )
            }

            LevelTestGenerateResponse(
                attemptId = attemptId.value,
                referenceGradeLabel = referenceGradeLabel,
                questions = questions
            )
        }
    }

    suspend fun submitTest(userId: Int, attemptId: Int, answers: List<LevelTestAnswer>): LevelTestSubmitResponse =
        newSuspendedTransaction {
            val attemptRow = LevelTestAttempts.selectAll()
                .where { (LevelTestAttempts.id eq attemptId) and (LevelTestAttempts.userId eq userId) }
                .firstOrNull()
                ?: throw LevelTestNotFoundException()

            if (attemptRow[LevelTestAttempts.isSubmitted]) {
                throw LevelTestValidationException.AlreadySubmittedException()
            }

            val questionRows = LevelTestQuestions.selectAll()
                .where { LevelTestQuestions.attemptId eq attemptId }
                .associateBy { it[LevelTestQuestions.id] }
            if (questionRows.isEmpty()) throw LevelTestNotFoundException()

            val questionIds = questionRows.keys
            if (answers.any { it.questionId !in questionIds }) {
                throw LevelTestValidationException.InvalidAnswerException()
            }

            val choicesByQuestion = LevelTestChoices.selectAll()
                .where { LevelTestChoices.questionId inList questionIds }
                .groupBy { it[LevelTestChoices.questionId] }
                .mapValues { (_, rows) -> rows.sortedBy { it[LevelTestChoices.id] } }

            val results = answers.map { answer ->
                val questionRow = questionRows.getValue(answer.questionId)
                val choices = choicesByQuestion[answer.questionId].orEmpty()
                if (answer.selectedIndex !in choices.indices) {
                    throw LevelTestValidationException.InvalidAnswerException()
                }
                val correctIndex = choices.indexOfFirst { it[LevelTestChoices.isCorrect] }
                val subjectName = Subjects.selectAll()
                    .where { Subjects.id eq questionRow[LevelTestQuestions.subjectId] }
                    .first()[Subjects.name]

                LevelTestQuestionResultResponse(
                    questionId = answer.questionId,
                    subject = subjectName,
                    isCorrect = answer.selectedIndex == correctIndex,
                    selectedIndex = answer.selectedIndex,
                    correctIndex = correctIndex,
                    explanation = choices[correctIndex][LevelTestChoices.explanation]
                )
            }

            val subjectScores = results.groupBy { it.subject }.map { (subject, subjectResults) ->
                val correctCount = subjectResults.count { it.isCorrect }
                val totalCount = subjectResults.size
                val subjectId = Subjects.selectAll()
                    .where { Subjects.name eq subject }
                    .first()[Subjects.id]

                LevelTestResults.insert {
                    it[this.userId] = userId
                    it[this.subjectId] = subjectId
                    // score는 0~100 정답률(%)로 저장한다. 문항 수가 시도마다 다를 수 있어 원시 정답
                    // 개수만 저장하면 나중에 총 문항 수 없이는 등급을 다시 계산할 수 없기 때문.
                    it[score] = ((correctCount.toDouble() / totalCount) * 100).roundToInt()
                    it[createdAt] = OffsetDateTime.now()
                }

                LevelTestSubjectScoreResponse(
                    subject = subject,
                    correctCount = correctCount,
                    totalCount = totalCount,
                    tier = computeTier(correctCount, totalCount)
                )
            }

            LevelTestAttempts.update({ LevelTestAttempts.id eq attemptId }) {
                it[isSubmitted] = true
            }

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

    private fun findOrCreateSubject(name: String): Int {
        Subjects.selectAll().where { Subjects.name eq name }.firstOrNull()?.let {
            return it[Subjects.id]
        }
        return Subjects.insert { it[this.name] = name } get Subjects.id
    }

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
