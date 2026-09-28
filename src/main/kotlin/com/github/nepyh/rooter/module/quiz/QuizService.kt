package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.module.planboard.orderedChaptersInRange
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

/** generateQuiz 의 첫 트랜잭션 결과 — 이미 있는 퀴즈를 돌려줄지, LLM 으로 새로 만들지 */
private sealed interface QuizPreparation {
    data class Existing(val quiz: QuizResponse) : QuizPreparation
    data class New(val dailyPlanId: Int, val context: String) : QuizPreparation
}

class QuizService(
    private val llmClient: QuizLlmClient
) {

    /**
     * 그날의 일일 퀴즈를 만든다. 이미 만들어진 퀴즈가 있으면 새로 만들지 않고 그대로 돌려준다
     * (앱이 태스크를 완료할 때마다 불러도 문제가 계속 쌓이지 않게).
     *
     * LLM 호출(수 초)은 DB 트랜잭션 밖에서 하고, 저장할 때 일일 계획 행을 잠근 뒤 한 번 더 확인해서
     * 동시에 두 번 요청돼도 한 세트만 저장되게 한다.
     */
    suspend fun generateQuiz(userId: Int, date: LocalDate): QuizResponse {
        val prepared = newSuspendedTransaction {
            // 소유자 확인을 위해 daily_plans + plan_boards 를 조인해야 해서 Table DSL 을 쓴다 (집계/조인은 DAO 대상 아님).
            val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where { (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq userId) }
                .firstOrNull()
                ?: throw QuizValidationException.NoPlanForDateException()

            val dailyPlanId = dailyPlanRow[DailyPlanTable.id].value
            existingQuiz(dailyPlanId, date)?.let { return@newSuspendedTransaction QuizPreparation.Existing(it) }

            val planBoardId = dailyPlanRow[DailyPlanTable.planBoardId].value
            val chapterNames = chapterNamesForPlanBoard(planBoardId)
            val completedTaskNames = PlanTaskRow.find {
                (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.isCompleted eq true)
            }.map { it.taskName }

            QuizPreparation.New(
                dailyPlanId = dailyPlanId,
                context = buildString {
                    appendLine("학습 범위: ${chapterNames.joinToString(", ").ifBlank { "지정 안 됨" }}")
                    appendLine("오늘 완료한 학습: ${completedTaskNames.joinToString(", ").ifBlank { "없음" }}")
                }
            )
        }

        val (dailyPlanId, context) = when (prepared) {
            is QuizPreparation.Existing -> return prepared.quiz
            is QuizPreparation.New -> prepared
        }

        val generated = llmClient.generateQuestions(context, DEFAULT_QUESTION_COUNT)
        if (generated.isEmpty()) throw QuizValidationException.QuizGenerationFailedException()

        return newSuspendedTransaction {
            // 같은 일일 계획에 대한 동시 생성 요청을 직렬화한다 — 먼저 저장한 쪽의 퀴즈를 뒤 요청도 그대로 받는다
            DailyPlanTable.selectAll().where { DailyPlanTable.id eq dailyPlanId }.forUpdate().single()
            existingQuiz(dailyPlanId, date)?.let { return@newSuspendedTransaction it }

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
    }

    /** 이미 저장된 퀴즈가 있으면 응답 형태로, 없으면 null. 트랜잭션 안에서 호출한다. */
    private fun existingQuiz(dailyPlanId: Int, quizDate: LocalDate): QuizResponse? {
        val questions = DailyQuizQuestionRow.find { DailyQuizQuestionTable.dailyPlanId eq dailyPlanId }
            .orderBy(DailyQuizQuestionTable.id to SortOrder.ASC)
            .map { question ->
                val choices = DailyQuizChoiceRow.find { DailyQuizChoiceTable.questionId eq question.id }
                    .orderBy(DailyQuizChoiceTable.id to SortOrder.ASC)
                    .map { QuizChoiceResponse(id = it.id.value, choiceText = it.choiceText) }

                QuizQuestionResponse(id = question.id.value, questionText = question.questionText, choices = choices)
            }
        return if (questions.isEmpty()) null else QuizResponse(dailyPlanId = dailyPlanId, quizDate = quizDate.toString(), questions = questions)
    }

    suspend fun getQuiz(userId: Int, dailyPlanId: Int): QuizResponse = newSuspendedTransaction {
        // 소유자 확인용 조인 조회 (위 generateQuiz 와 같은 이유로 Table DSL 유지)
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        existingQuiz(dailyPlanId, dailyPlanRow[DailyPlanTable.planDate]) ?: throw QuizNotFoundException()
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
            // 범위 선택은 planboard 의 트리 순회 순서 함수를 그대로 쓴다.
            // 예전엔 chapter_order 만 비교해서 두 대단원의 소단원이 섞여 들어왔다.
            orderedChaptersInRange(planSubject.textbook, planSubject.startChapter, planSubject.endChapter)
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
