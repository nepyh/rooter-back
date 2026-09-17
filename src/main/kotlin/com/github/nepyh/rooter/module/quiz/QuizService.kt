package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
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
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
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
        // 소유자 확인을 위해 daily_plans + plan_boards 를 조인해야 해서 Table DSL 을 쓴다 (집계/조인은 DAO 대상 아님).
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizValidationException.NoPlanForDateException()

        val dailyPlanId = dailyPlanRow[DailyPlanTable.id].value
        val planBoardId = dailyPlanRow[DailyPlanTable.planBoardId].value

        val chapterNames = chapterNamesForPlanBoard(planBoardId)
        val completedTaskNames = PlanTaskRow.find {
            (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.isCompleted eq true)
        }.map { it.taskName }

        val context = buildString {
            appendLine("학습 범위: ${chapterNames.joinToString(", ").ifBlank { "지정 안 됨" }}")
            appendLine("오늘 완료한 학습: ${completedTaskNames.joinToString(", ").ifBlank { "없음" }}")
        }

        val generated = llmClient.generateQuestions(context, DEFAULT_QUESTION_COUNT)

        val questions = generated.map { question ->
            val quizQuestion = DailyQuizQuestionRow.new {
                this.dailyPlan = DailyPlanRow[dailyPlanId]
                questionText = question.questionText
            }

            val choices = question.choices.mapIndexed { index, choiceText ->
                val choice = DailyQuizChoiceRow.new {
                    this.question = quizQuestion
                    this.choiceText = choiceText
                    isCorrect = index == question.correctIndex
                }
                QuizChoiceResponse(id = choice.id.value, choiceText = choiceText)
            }

            QuizQuestionResponse(id = quizQuestion.id.value, questionText = question.questionText, choices = choices)
        }

        QuizResponse(dailyPlanId = dailyPlanId, quizDate = date.toString(), questions = questions)
    }

    suspend fun getQuiz(userId: Int, dailyPlanId: Int): QuizResponse = newSuspendedTransaction {
        // 소유자 확인용 조인 조회 (위 generateQuiz 와 같은 이유로 Table DSL 유지)
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        val questions = DailyQuizQuestionRow.find { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }
            .map { question ->
                val choices = DailyQuizChoiceRow.find { DailyQuizChoiceTable.questionId eq question.id }
                    .map { QuizChoiceResponse(id = it.id.value, choiceText = it.choiceText) }

                QuizQuestionResponse(
                    id = question.id.value,
                    questionText = question.questionText,
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
        // 소유자 확인용 조인 조회 (위 generateQuiz 와 같은 이유로 Table DSL 유지)
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        val planBoardId = dailyPlanRow[DailyPlanTable.planBoardId].value
        val quizDate = dailyPlanRow[DailyPlanTable.planDate]

        val questionIds = DailyQuizQuestionRow.find { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }
            .map { it.id.value }
            .toSet()

        if (questionIds.isEmpty()) throw QuizNotFoundException()

        // 이미 제출했는지 확인하려면 daily_quiz_attempts + daily_quiz_choices 조인이 필요해 Table DSL 유지
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
            val choiceRow = DailyQuizChoiceRow.find {
                (DailyQuizChoiceTable.id eq answer.selectedChoiceId) and (DailyQuizChoiceTable.questionId eq answer.questionId)
            }.firstOrNull() ?: throw QuizValidationException.InvalidAnswerException()

            DailyQuizAttemptRow.new {
                this.userId = userId
                selectedChoice = choiceRow
                createdAt = OffsetDateTime.now()
            }

            if (choiceRow.isCorrect) {
                correctCount++
            } else {
                wrongQuestionTexts.add(DailyQuizQuestionRow[answer.questionId].questionText)
            }
        }

        val weakAreas = mutableListOf<WeakAreaSummary>()
        val insertedReviewTasks = mutableListOf<InsertedReviewTaskResponse>()

        if (wrongQuestionTexts.isNotEmpty()) {
            val chapterNames = chapterNamesForPlanBoard(planBoardId)
            val boardEndDate = PlanBoardRow[planBoardId].endDate

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
                val startTime = lastTaskEndTime(targetDailyPlan.first.id.value) ?: LocalTime.of(9, 0)
                val endTime = startTime.plusMinutes(REVIEW_TASK_MINUTES.toLong())

                PlanTaskRow.new {
                    this.dailyPlan = targetDailyPlan.first
                    this.taskName = taskName
                    this.startTime = startTime
                    this.endTime = endTime
                    estimatedMinutes = REVIEW_TASK_MINUTES
                }

                insertedReviewTasks.add(
                    InsertedReviewTaskResponse(
                        dailyPlanId = targetDailyPlan.first.id.value,
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
        val planSubjects = PlanSubjectRow.find { PlanSubjectTable.planBoardId eq planBoardId }.toList()

        return planSubjects.flatMap { planSubject ->
            val startChapter = planSubject.startChapter
            val endChapter = planSubject.endChapter

            ChapterRow.find { ChapterTable.textbookId eq planSubject.textbook.id.value }
                .orderBy(ChapterTable.chapterOrder to SortOrder.ASC)
                .filter { it.chapterOrder in startChapter.chapterOrder..endChapter.chapterOrder }
                .map { it.chapterName }
        }
    }

    private fun findOrCreateNextDailyPlan(
        planBoardId: Int,
        afterDate: LocalDate,
        boardEndDate: LocalDate
    ): Pair<DailyPlanRow, LocalDate>? {
        val existing = DailyPlanRow.find {
            (DailyPlanTable.planBoardId eq planBoardId) and (DailyPlanTable.planDate greater afterDate)
        }
            .orderBy(DailyPlanTable.planDate to SortOrder.ASC)
            .firstOrNull()

        if (existing != null) {
            return existing to existing.planDate
        }

        val nextDate = afterDate.plusDays(1)
        if (nextDate.isAfter(boardEndDate)) return null

        val newPlan = DailyPlanRow.new {
            planBoard = PlanBoardRow[planBoardId]
            planDate = nextDate
        }

        return newPlan to nextDate
    }

    private fun lastTaskEndTime(dailyPlanId: Int): LocalTime? =
        PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }
            .orderBy(PlanTaskTable.endTime to SortOrder.DESC)
            .firstOrNull()
            ?.endTime
}
