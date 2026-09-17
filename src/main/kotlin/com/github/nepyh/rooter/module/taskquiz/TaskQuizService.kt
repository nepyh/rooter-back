package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizAnswer
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizChoiceResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizQuestionResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizSubmitResponse
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizNotFoundException
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizValidationException
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttempts
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoices
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestions
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

const val MAX_ATTEMPTS = 3 // 최초 1회 + 재시도 2회
const val PASS_THRESHOLD = 4 // 5문항 중 4개 이상 정답이면 통과
const val QUESTION_COUNT = 5
const val RETRY_DELAY_MINUTES = 10L

class TaskQuizService(
    private val llmClient: TaskQuizLlmClient
) {

    /** 스케줄러가 호출. taskName은 findDue 시점에 이미 조회해둔 값을 그대로 받는다. */
    suspend fun generateAttempt(planTaskId: Int, attemptNumber: Int, taskName: String) {
        val generated = llmClient.generateQuestions(taskName)
        if (generated.isEmpty()) return // AI 생성 실패 시 이번 attempt는 건너뜀 (다음 스케줄 대상이 되진 않음)

        newSuspendedTransaction {
            val attemptId = TaskQuizAttempts.insert {
                it[this.planTaskId] = planTaskId
                it[this.attemptNumber] = attemptNumber
                it[totalCount] = generated.size
                it[createdAt] = OffsetDateTime.now()
            } get TaskQuizAttempts.id

            generated.forEach { question ->
                val questionId = TaskQuizQuestions.insert {
                    it[this.attemptId] = attemptId.value
                    it[questionText] = question.question_text
                } get TaskQuizQuestions.id

                question.choices.forEachIndexed { index, choiceText ->
                    TaskQuizChoices.insert {
                        it[this.questionId] = questionId
                        it[this.choiceText] = choiceText
                        it[isCorrect] = index == question.correct_index
                        it[explanation] = question.explanation
                    }
                }
            }
        }
    }

    fun getCurrentQuiz(userId: Int, planTaskId: Int): TaskQuizResponse = transaction {
        requireOwnedTask(userId, planTaskId)

        val latestAttempt = TaskQuizAttempts.selectAll()
            .where { TaskQuizAttempts.planTaskId eq planTaskId }
            .orderBy(TaskQuizAttempts.attemptNumber to SortOrder.DESC)
            .firstOrNull() ?: throw TaskQuizNotFoundException()

        val attemptId = latestAttempt[TaskQuizAttempts.id].value
        val questions = TaskQuizQuestions.selectAll()
            .where { TaskQuizQuestions.attemptId eq attemptId }
            .map { questionRow ->
                val questionId = questionRow[TaskQuizQuestions.id]
                val choices = TaskQuizChoices.selectAll()
                    .where { TaskQuizChoices.questionId eq questionId }
                    .map { TaskQuizChoiceResponse(id = it[TaskQuizChoices.id], choiceText = it[TaskQuizChoices.choiceText]) }

                TaskQuizQuestionResponse(
                    id = questionId,
                    questionText = questionRow[TaskQuizQuestions.questionText],
                    choices = choices
                )
            }

        TaskQuizResponse(
            planTaskId = planTaskId,
            attemptNumber = latestAttempt[TaskQuizAttempts.attemptNumber],
            questions = questions
        )
    }

    fun submitQuiz(userId: Int, planTaskId: Int, answers: List<TaskQuizAnswer>): TaskQuizSubmitResponse = transaction {
        requireOwnedTask(userId, planTaskId)

        val attemptRow = TaskQuizAttempts.selectAll()
            .where { TaskQuizAttempts.planTaskId eq planTaskId }
            .orderBy(TaskQuizAttempts.attemptNumber to SortOrder.DESC)
            .firstOrNull() ?: throw TaskQuizNotFoundException()

        if (attemptRow[TaskQuizAttempts.passed] != null) {
            throw TaskQuizValidationException.AlreadySubmittedException()
        }

        val attemptId = attemptRow[TaskQuizAttempts.id].value
        val attemptNumber = attemptRow[TaskQuizAttempts.attemptNumber]
        val totalCount = attemptRow[TaskQuizAttempts.totalCount]

        val questionIds = TaskQuizQuestions.selectAll()
            .where { TaskQuizQuestions.attemptId eq attemptId }
            .map { it[TaskQuizQuestions.id] }
            .toSet()
        if (answers.any { it.questionId !in questionIds }) {
            throw TaskQuizValidationException.InvalidAnswerException()
        }

        val correctChoiceIds = TaskQuizChoices.selectAll()
            .where { (TaskQuizChoices.questionId inList questionIds) and (TaskQuizChoices.isCorrect eq true) }
            .map { it[TaskQuizChoices.id] }
            .toSet()

        val correctCount = answers.count { it.selectedChoiceId in correctChoiceIds }
        val passed = correctCount >= PASS_THRESHOLD

        TaskQuizAttempts.update({ TaskQuizAttempts.id eq attemptId }) {
            it[this.correctCount] = correctCount
            it[this.passed] = passed
        }

        var retryScheduled = false
        var taskInvalidated = false

        if (passed) {
            // 퀴즈 통과 = 완료 확인 자체이므로 체크 여부와 무관하게 완료 처리
            PlanTaskTable.update({ PlanTaskTable.id eq planTaskId }) {
                it[isCompleted] = true
            }
        } else if (attemptNumber < MAX_ATTEMPTS) {
            retryScheduled = true // 스케줄러가 RETRY_DELAY_MINUTES 뒤 다음 attempt를 자동 생성
        } else {
            // 최초 1회 + 재시도 2회 모두 실패 -> 미완료로 확정 (잔디 색에 반영됨)
            PlanTaskTable.update({ PlanTaskTable.id eq planTaskId }) {
                it[isCompleted] = false
            }
            taskInvalidated = true
        }

        TaskQuizSubmitResponse(
            attemptNumber = attemptNumber,
            correctCount = correctCount,
            totalCount = totalCount,
            passed = passed,
            retryScheduled = retryScheduled,
            taskInvalidated = taskInvalidated
        )
    }

    private fun requireOwnedTask(userId: Int, planTaskId: Int) {
        (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (PlanTaskTable.id eq planTaskId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull() ?: throw TaskQuizNotFoundException()
    }
}
