package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.module.planboard.model.Chapters
import com.github.nepyh.rooter.module.planboard.model.DailyPlans
import com.github.nepyh.rooter.module.planboard.model.PlanBoards
import com.github.nepyh.rooter.module.planboard.model.PlanSubjects
import com.github.nepyh.rooter.module.planboard.model.PlanTasks
import com.github.nepyh.rooter.module.quiz.dto.InsertedReviewTaskResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizAnswerSubmission
import com.github.nepyh.rooter.module.quiz.dto.QuizChoiceResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizQuestionResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizResultResponse
import com.github.nepyh.rooter.module.quiz.dto.WeakAreaSummary
import com.github.nepyh.rooter.module.quiz.exception.QuizNotFoundException
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttempts
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoices
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestions
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
        val dailyPlanRow = (DailyPlans innerJoin PlanBoards)
            .selectAll()
            .where { (DailyPlans.planDate eq date) and (PlanBoards.userId eq userId) }
            .firstOrNull()
            ?: throw QuizValidationException.NoPlanForDateException()

        val dailyPlanId = dailyPlanRow[DailyPlans.id]
        val planBoardId = dailyPlanRow[DailyPlans.planBoardId]

        val chapterNames = chapterNamesForPlanBoard(planBoardId)
        val completedTaskNames = PlanTasks.selectAll()
            .where { (PlanTasks.dailyPlanId eq dailyPlanId) and (PlanTasks.isCompleted eq true) }
            .map { it[PlanTasks.taskName] }

        val context = buildString {
            appendLine("학습 범위: ${chapterNames.joinToString(", ").ifBlank { "지정 안 됨" }}")
            appendLine("오늘 완료한 학습: ${completedTaskNames.joinToString(", ").ifBlank { "없음" }}")
        }

        val generated = llmClient.generateQuestions(context, DEFAULT_QUESTION_COUNT)

        val questions = generated.map { question ->
            val questionId = DailyQuizQuestions.insert {
                it[this.dailyPlanId] = dailyPlanId
                it[questionText] = question.questionText
            } get DailyQuizQuestions.id

            val choices = question.choices.mapIndexed { index, choiceText ->
                val choiceId = DailyQuizChoices.insert {
                    it[this.questionId] = questionId
                    it[this.choiceText] = choiceText
                    it[isCorrect] = index == question.correctIndex
                } get DailyQuizChoices.id
                QuizChoiceResponse(id = choiceId, choiceText = choiceText)
            }

            QuizQuestionResponse(id = questionId, questionText = question.questionText, choices = choices)
        }

        QuizResponse(dailyPlanId = dailyPlanId, quizDate = date.toString(), questions = questions)
    }

    suspend fun getQuiz(userId: Int, dailyPlanId: Int): QuizResponse = newSuspendedTransaction {
        val dailyPlanRow = (DailyPlans innerJoin PlanBoards)
            .selectAll()
            .where { (DailyPlans.id eq dailyPlanId) and (PlanBoards.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        val questions = DailyQuizQuestions.selectAll()
            .where { DailyQuizQuestions.dailyPlanId eq dailyPlanId }
            .map { questionRow ->
                val questionId = questionRow[DailyQuizQuestions.id]
                val choices = DailyQuizChoices.selectAll()
                    .where { DailyQuizChoices.questionId eq questionId }
                    .map { QuizChoiceResponse(id = it[DailyQuizChoices.id], choiceText = it[DailyQuizChoices.choiceText]) }

                QuizQuestionResponse(
                    id = questionId,
                    questionText = questionRow[DailyQuizQuestions.questionText],
                    choices = choices
                )
            }

        if (questions.isEmpty()) throw QuizNotFoundException()

        QuizResponse(dailyPlanId = dailyPlanId, quizDate = dailyPlanRow[DailyPlans.planDate].toString(), questions = questions)
    }

    suspend fun submitQuiz(
        userId: Int,
        dailyPlanId: Int,
        answers: List<QuizAnswerSubmission>
    ): QuizResultResponse = newSuspendedTransaction {
        val dailyPlanRow = (DailyPlans innerJoin PlanBoards)
            .selectAll()
            .where { (DailyPlans.id eq dailyPlanId) and (PlanBoards.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        val planBoardId = dailyPlanRow[DailyPlans.planBoardId]
        val quizDate = dailyPlanRow[DailyPlans.planDate]

        val questionIds = DailyQuizQuestions.selectAll()
            .where { DailyQuizQuestions.dailyPlanId eq dailyPlanId }
            .map { it[DailyQuizQuestions.id] }
            .toSet()

        if (questionIds.isEmpty()) throw QuizNotFoundException()

        val alreadySubmitted = (DailyQuizAttempts innerJoin DailyQuizChoices)
            .selectAll()
            .where { DailyQuizChoices.questionId inList questionIds }
            .any { it[DailyQuizAttempts.userId] == userId }
        if (alreadySubmitted) throw QuizValidationException.AlreadySubmittedException()

        if (answers.any { it.questionId !in questionIds }) {
            throw QuizValidationException.InvalidAnswerException()
        }

        var correctCount = 0
        val wrongQuestionTexts = mutableListOf<String>()

        for (answer in answers) {
            val choiceRow = DailyQuizChoices.selectAll()
                .where { (DailyQuizChoices.id eq answer.selectedChoiceId) and (DailyQuizChoices.questionId eq answer.questionId) }
                .firstOrNull()
                ?: throw QuizValidationException.InvalidAnswerException()

            DailyQuizAttempts.insert {
                it[this.userId] = userId
                it[selectedChoiceId] = answer.selectedChoiceId
                it[createdAt] = OffsetDateTime.now()
            }

            if (choiceRow[DailyQuizChoices.isCorrect]) {
                correctCount++
            } else {
                val questionText = DailyQuizQuestions.selectAll()
                    .where { DailyQuizQuestions.id eq answer.questionId }
                    .first()[DailyQuizQuestions.questionText]
                wrongQuestionTexts.add(questionText)
            }
        }

        val weakAreas = mutableListOf<WeakAreaSummary>()
        val insertedReviewTasks = mutableListOf<InsertedReviewTaskResponse>()

        if (wrongQuestionTexts.isNotEmpty()) {
            val chapterNames = chapterNamesForPlanBoard(planBoardId)
            val boardEndDate = PlanBoards.selectAll()
                .where { PlanBoards.id eq planBoardId }
                .first()[PlanBoards.endDate]

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

                PlanTasks.insert {
                    it[PlanTasks.dailyPlanId] = targetDailyPlan.first
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
        val planSubjects = PlanSubjects.selectAll()
            .where { PlanSubjects.planBoardId eq planBoardId }
            .toList()

        return planSubjects.flatMap { planSubject ->
            val textbookId = planSubject[PlanSubjects.textbookId]
            val startOrder = Chapters.selectAll()
                .where { Chapters.id eq planSubject[PlanSubjects.startChapterId] }
                .first()[Chapters.chapterOrder]
            val endOrder = Chapters.selectAll()
                .where { Chapters.id eq planSubject[PlanSubjects.endChapterId] }
                .first()[Chapters.chapterOrder]

            Chapters.selectAll()
                .where { Chapters.textbookId eq textbookId }
                .orderBy(Chapters.chapterOrder to SortOrder.ASC)
                .map { it[Chapters.chapterName] to it[Chapters.chapterOrder] }
                .filter { (_, order) -> order in startOrder..endOrder }
                .map { (name, _) -> name }
        }
    }

    private fun findOrCreateNextDailyPlan(
        planBoardId: Int,
        afterDate: LocalDate,
        boardEndDate: LocalDate
    ): Pair<Int, LocalDate>? {
        val existing = DailyPlans.selectAll()
            .where { (DailyPlans.planBoardId eq planBoardId) and (DailyPlans.planDate greater afterDate) }
            .orderBy(DailyPlans.planDate to SortOrder.ASC)
            .firstOrNull()

        if (existing != null) {
            return existing[DailyPlans.id] to existing[DailyPlans.planDate]
        }

        val nextDate = afterDate.plusDays(1)
        if (nextDate.isAfter(boardEndDate)) return null

        val newId = DailyPlans.insert {
            it[this.planBoardId] = planBoardId
            it[planDate] = nextDate
        } get DailyPlans.id

        return newId to nextDate
    }

    private fun lastTaskEndTime(dailyPlanId: Int): LocalTime? =
        PlanTasks.selectAll()
            .where { PlanTasks.dailyPlanId eq dailyPlanId }
            .orderBy(PlanTasks.endTime to SortOrder.DESC)
            .firstOrNull()
            ?.get(PlanTasks.endTime)
}
