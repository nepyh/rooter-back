package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.orderedChaptersInRange
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizAnswer
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizChoiceResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizQuestionResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizSubmitResponse
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizNotFoundException
import com.github.nepyh.rooter.module.taskquiz.exception.TaskQuizValidationException
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizChoiceTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionRow
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizQuestionTable
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        val (gradeLabel, studyScope) = newSuspendedTransaction { quizContextOf(planTaskId) }
        val generated = llmClient.generateQuestions(taskName, gradeLabel, studyScope)
        if (generated.isEmpty()) return // AI 생성 실패 시 이번 attempt는 건너뜀 (다음 스케줄 대상이 되진 않음)

        newSuspendedTransaction {
            val attempt = TaskQuizAttemptRow.new {
                this.planTaskId = EntityID(planTaskId, PlanTaskTable)
                this.attemptNumber = attemptNumber
                totalCount = generated.size
                createdAt = OffsetDateTime.now()
            }

            generated.forEach { question ->
                val quizQuestion = TaskQuizQuestionRow.new {
                    this.attempt = attempt
                    questionText = question.question_text
                }

                question.choices.forEachIndexed { index, choiceText ->
                    TaskQuizChoiceRow.new {
                        this.question = quizQuestion
                        this.choiceText = choiceText
                        isCorrect = index == question.correct_index
                        explanation = question.explanation
                    }
                }
            }
        }
    }

    fun getCurrentQuiz(userId: Int, planTaskId: Int): TaskQuizResponse = transaction {
        requireOwnedTask(userId, planTaskId)

        val latestAttempt = TaskQuizAttemptRow.find { TaskQuizAttemptTable.planTaskId eq planTaskId }
            .orderBy(TaskQuizAttemptTable.attemptNumber to SortOrder.DESC)
            .firstOrNull() ?: throw TaskQuizNotFoundException()

        val questions = TaskQuizQuestionRow.find { TaskQuizQuestionTable.attemptId eq latestAttempt.id }
            .map { question ->
                val choices = TaskQuizChoiceRow.find { TaskQuizChoiceTable.questionId eq question.id }
                    .map { TaskQuizChoiceResponse(id = it.id.value, choiceText = it.choiceText) }

                TaskQuizQuestionResponse(
                    id = question.id.value,
                    questionText = question.questionText,
                    choices = choices
                )
            }

        TaskQuizResponse(
            planTaskId = planTaskId,
            attemptNumber = latestAttempt.attemptNumber,
            questions = questions
        )
    }

    fun submitQuiz(userId: Int, planTaskId: Int, answers: List<TaskQuizAnswer>): TaskQuizSubmitResponse = transaction {
        requireOwnedTask(userId, planTaskId)

        val attempt = TaskQuizAttemptRow.find { TaskQuizAttemptTable.planTaskId eq planTaskId }
            .orderBy(TaskQuizAttemptTable.attemptNumber to SortOrder.DESC)
            .firstOrNull() ?: throw TaskQuizNotFoundException()

        if (attempt.passed != null) {
            throw TaskQuizValidationException.AlreadySubmittedException()
        }

        val attemptNumber = attempt.attemptNumber
        val totalCount = attempt.totalCount

        val questionIds = TaskQuizQuestionRow.find { TaskQuizQuestionTable.attemptId eq attempt.id }
            .map { it.id.value }
            .toSet()
        if (answers.any { it.questionId !in questionIds }) {
            throw TaskQuizValidationException.InvalidAnswerException()
        }

        val correctChoiceIds = TaskQuizChoiceRow.find {
            (TaskQuizChoiceTable.questionId inList questionIds) and (TaskQuizChoiceTable.isCorrect eq true)
        }
            .map { it.id.value }
            .toSet()

        val correctCount = answers.count { it.selectedChoiceId in correctChoiceIds }
        val passed = correctCount >= PASS_THRESHOLD

        attempt.correctCount = correctCount
        attempt.passed = passed

        var retryScheduled = false
        var taskInvalidated = false

        if (passed) {
            // 퀴즈 통과 = 완료 확인 자체이므로 체크 여부와 무관하게 완료 처리
            PlanTaskRow[planTaskId].isCompleted = true
        } else if (attemptNumber < MAX_ATTEMPTS) {
            retryScheduled = true // 스케줄러가 RETRY_DELAY_MINUTES 뒤 다음 attempt를 자동 생성
        } else {
            // 최초 1회 + 재시도 2회 모두 실패 -> 미완료로 확정 (잔디 색에 반영됨)
            PlanTaskRow[planTaskId].isCompleted = false
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
        // plan_tasks -> daily_plans -> plan_boards 소유자 확인 조인이라 Table DSL 을 쓴다
        (PlanTaskTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (PlanTaskTable.id eq planTaskId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull() ?: throw TaskQuizNotFoundException()
    }

    /**
     * 퀴즈 출제에 쓸 학년 표시와 학습 범위(과목·단원). 사용자가 직접 추가한 태스크는 이름만 있어서
     * ("영어 단어 30개 외우기" 등) 이게 없으면 AI 가 "학생이 완료한 활동은?" 같은 문제를 냈다.
     */
    private fun quizContextOf(planTaskId: Int): Pair<String, String> {
        val board = PlanTaskRow.findById(planTaskId)?.dailyPlan?.planBoard
            ?: return "중학생(학년 정보 없음)" to "지정 안 됨"

        val grade = StudentProfileRow.find { StudentProfileTable.user eq board.user.id }.firstOrNull()?.grade
        val gradeLabel = grade?.let { "중학교 ${it}학년" } ?: "중학생(학년 정보 없음)"

        val studyScope = PlanSubjectRow.find { PlanSubjectTable.planBoardId eq board.id }.joinToString("\n") { subject ->
            val chapters = orderedChaptersInRange(subject.textbook, subject.startChapter, subject.endChapter)
                .map { it.chapterName } + listOfNotNull(subject.customRangeText?.takeIf { it.isNotBlank() })
            "${subject.textbook.subject.name}: ${chapters.joinToString(", ")}"
        }.ifBlank { "지정 안 됨" }

        return gradeLabel to studyScope
    }

}
