package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.quiz.dto.InsertedReviewTaskResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizAnswerSubmission
import com.github.nepyh.rooter.module.quiz.dto.QuizChoiceResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizQuestionResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizResultResponse
import com.github.nepyh.rooter.module.quiz.dto.WeakAreaSummary
import com.github.nepyh.rooter.module.quiz.exception.QuizNotFoundException
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

private const val DEFAULT_QUESTION_COUNT = 5
private const val REVIEW_TASK_MINUTES = 20

class QuizService(
    private val llmClient: QuizLlmClient
) {

    suspend fun generateQuiz(userId: Int, date: LocalDate): QuizResponse = newSuspendedTransaction {
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizValidationException.NoPlanForDateException()

        val dailyPlanId = dailyPlanRow[DailyPlanTable.id].value
        val planBoardId = dailyPlanRow[DailyPlanTable.planBoardId].value

        val chapterNames = chapterNamesForPlanBoard(planBoardId)
        val completedTaskNames = PlanTaskTable.selectAll()
            .where { (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.isCompleted eq true) }
            .map { it[PlanTaskTable.taskName] }

        val context = buildString {
            appendLine("학습 범위: ${chapterNames.joinToString(", ").ifBlank { "지정 안 됨" }}")
            appendLine("오늘 완료한 학습: ${completedTaskNames.joinToString(", ").ifBlank { "없음" }}")
        }

        val generated = llmClient.generateQuestions(context, DEFAULT_QUESTION_COUNT)

        val questions = generated.map { question ->
            val questionId = DailyQuizQuestionTable.insert {
                it[this.dailyPlanId] = dailyPlanId
                it[questionText] = question.questionText
            } get DailyQuizQuestionTable.id

            val choices = question.choices.mapIndexed { index, choiceText ->
                val choiceId = DailyQuizChoiceTable.insert {
                    it[this.questionId] = questionId
                    it[this.choiceText] = choiceText
                    it[isCorrect] = index == question.correctIndex
                } get DailyQuizChoiceTable.id
                QuizChoiceResponse(id = choiceId.value, choiceText = choiceText)
            }

            QuizQuestionResponse(id = questionId.value, questionText = question.questionText, choices = choices)
        }

        QuizResponse(dailyPlanId = dailyPlanId, quizDate = date.toString(), questions = questions)
    }

    suspend fun getQuiz(userId: Int, dailyPlanId: Int): QuizResponse = newSuspendedTransaction {
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        val questions = DailyQuizQuestionTable.selectAll()
            .where { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }
            .map { questionRow ->
                val questionId = questionRow[DailyQuizQuestionTable.id].value
                val choices = DailyQuizChoiceTable.selectAll()
                    .where { DailyQuizChoiceTable.questionId eq questionId }
                    .map { QuizChoiceResponse(id = it[DailyQuizChoiceTable.id].value, choiceText = it[DailyQuizChoiceTable.choiceText]) }

                QuizQuestionResponse(
                    id = questionId,
                    questionText = questionRow[DailyQuizQuestionTable.questionText],
                    choices = choices
                )
            }

        if (questions.isEmpty()) throw QuizNotFoundException()

        QuizResponse(dailyPlanId = dailyPlanId, quizDate = dailyPlanRow[DailyPlanTable.planDate].toString(), questions = questions)
    }

    suspend fun submitQuiz(
        userId: Int,
        dailyPlanId: Int,
        answers: List<QuizAnswerSubmission>
    ): QuizResultResponse = newSuspendedTransaction {
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        val planBoardId = dailyPlanRow[DailyPlanTable.planBoardId].value
        val quizDate = dailyPlanRow[DailyPlanTable.planDate]

        val questionIds = DailyQuizQuestionTable.selectAll()
            .where { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }
            .map { it[DailyQuizQuestionTable.id].value }
            .toSet()

        if (questionIds.isEmpty()) throw QuizNotFoundException()

        val alreadySubmitted = (DailyQuizAttemptTable innerJoin DailyQuizChoiceTable)
            .selectAll()
            .where { DailyQuizChoiceTable.questionId inList questionIds }
            .any { it[DailyQuizAttemptTable.userId] == userId }
        if (alreadySubmitted) throw QuizValidationException.AlreadySubmittedException()

        if (answers.any { it.questionId !in questionIds }) {
            throw QuizValidationException.InvalidAnswerException()
        }

        var correctCount = 0
        val wrongQuestionTexts = mutableListOf<String>()

        for (answer in answers) {
            val choiceRow = DailyQuizChoiceTable.selectAll()
                .where { (DailyQuizChoiceTable.id eq answer.selectedChoiceId) and (DailyQuizChoiceTable.questionId eq answer.questionId) }
                .firstOrNull()
                ?: throw QuizValidationException.InvalidAnswerException()

            DailyQuizAttemptTable.insert {
                it[this.userId] = userId
                it[selectedChoiceId] = answer.selectedChoiceId
                it[createdAt] = OffsetDateTime.now()
            }

            if (choiceRow[DailyQuizChoiceTable.isCorrect]) {
                correctCount++
            } else {
                val questionText = DailyQuizQuestionTable.selectAll()
                    .where { DailyQuizQuestionTable.id eq answer.questionId }
                    .first()[DailyQuizQuestionTable.questionText]
                wrongQuestionTexts.add(questionText)
            }
        }

        val weakAreas = mutableListOf<WeakAreaSummary>()
        val insertedReviewTasks = mutableListOf<InsertedReviewTaskResponse>()

        if (wrongQuestionTexts.isNotEmpty()) {
            val chapterNames = chapterNamesForPlanBoard(planBoardId)
            val boardEndDate = PlanBoardTable.selectAll()
                .where { PlanBoardTable.id eq planBoardId }
                .first()[PlanBoardTable.endDate]

            val suggestions = llmClient.analyzeWeakAreas(wrongQuestionTexts, chapterNames)

            for (suggestion in suggestions) {
                weakAreas.add(
                    WeakAreaSummary(
                        chapterName = suggestion.chapterName,
                        reviewTaskDescription = suggestion.reviewTaskDescription
                    )
                )

                val targetDailyPlan = findOrCreateNextDailyPlan(planBoardId, quizDate, boardEndDate) ?: continue
                val taskName = "복습: ${suggestion.reviewTaskDescription}".take(150)
                val startTime = lastTaskEndTime(targetDailyPlan.first) ?: LocalTime.of(9, 0)
                val endTime = startTime.plusMinutes(REVIEW_TASK_MINUTES.toLong())

                PlanTaskTable.insert {
                    it[PlanTaskTable.dailyPlanId] = targetDailyPlan.first
                    it[this.taskName] = taskName
                    it[this.startTime] = startTime
                    it[this.endTime] = endTime
                    it[estimatedMinutes] = REVIEW_TASK_MINUTES
                }

                insertedReviewTasks.add(
                    InsertedReviewTaskResponse(
                        dailyPlanId = targetDailyPlan.first,
                        planDate = targetDailyPlan.second.toString(),
                        taskName = taskName
                    )
                )
            }
        }

        QuizResultResponse(
            totalQuestions = answers.size,
            correctCount = correctCount,
            weakAreas = weakAreas,
            insertedReviewTasks = insertedReviewTasks
        )
    }

    private fun chapterNamesForPlanBoard(planBoardId: Int): List<String> {
        val planSubjects = PlanSubjectTable.selectAll()
            .where { PlanSubjectTable.planBoardId eq planBoardId }
            .toList()

        return planSubjects.flatMap { planSubject ->
            val textbookId = planSubject[PlanSubjectTable.textbookId]
            val startOrder = ChapterTable.selectAll()
                .where { ChapterTable.id eq planSubject[PlanSubjectTable.startChapterId] }
                .first()[ChapterTable.chapterOrder]
            val endOrder = ChapterTable.selectAll()
                .where { ChapterTable.id eq planSubject[PlanSubjectTable.endChapterId] }
                .first()[ChapterTable.chapterOrder]

            ChapterTable.selectAll()
                .where { ChapterTable.textbookId eq textbookId }
                .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
                .map { it[ChapterTable.chapterName] to it[ChapterTable.chapterOrder] }
                .filter { (_, order) -> order in startOrder..endOrder }
                .map { (name, _) -> name }
        }
    }

    private fun findOrCreateNextDailyPlan(
        planBoardId: Int,
        afterDate: LocalDate,
        boardEndDate: LocalDate
    ): Pair<Int, LocalDate>? {
        val existing = DailyPlanTable.selectAll()
            .where { (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate greater afterDate) }
            .orderBy(DailyPlanTable.planDate to SortOrder.ASC)
            .firstOrNull()

        if (existing != null) {
            return existing[DailyPlanTable.id].value to existing[DailyPlanTable.planDate]
        }

        val nextDate = afterDate.plusDays(1)
        if (nextDate.isAfter(boardEndDate)) return null

        val newId = DailyPlanTable.insert {
            it[this.planBoardId] = planBoardId
            it[planDate] = nextDate
        } get DailyPlanTable.id

        return newId.value to nextDate
    }

    private fun lastTaskEndTime(dailyPlanId: Int): LocalTime? =
        PlanTaskTable.selectAll()
            .where { PlanTaskTable.dailyPlanId eq dailyPlanId }
            .orderBy(PlanTaskTable.endTime to SortOrder.DESC)
            .firstOrNull()
            ?.get(PlanTaskTable.endTime)
}
