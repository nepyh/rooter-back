package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
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
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.LocalTime
import java.time.OffsetDateTime

const val MAX_ATTEMPTS = 3 // 최초 1회 + 재시도 2회
const val PASS_THRESHOLD = 4 // 5문항 중 4개 이상 정답이면 통과
const val QUESTION_COUNT = 5
const val RETRY_DELAY_MINUTES = 10L

class TaskQuizService(
    private val llmClient: TaskQuizLlmClient,
    private val clock: Clock = Clock.systemUTC()
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
        var shiftedTasks = emptyList<PlanTaskResponse>()

        if (passed) {
            // 퀴즈 통과 = 완료 확인 자체이므로 체크 여부와 무관하게 완료 처리
            PlanTaskRow[planTaskId].isCompleted = true
        } else if (attemptNumber < MAX_ATTEMPTS) {
            retryScheduled = true // 스케줄러가 RETRY_DELAY_MINUTES 뒤 다음 attempt를 자동 생성
            // 재시도 퀴즈를 풀 시간만큼 오늘 남은 계획을 뒤로 민다 (퀴즈와 다음 공부가 겹치지 않게)
            shiftedTasks = shiftRemainingTasksForRetry(planTaskId)
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
            taskInvalidated = taskInvalidated,
            shiftedTasks = shiftedTasks
        )
    }

    /**
     * 퀴즈에서 떨어진 태스크와 같은 날의, 아직 시작 안 한 미완료 태스크를 [QUIZ_FAIL_SHIFT_MINUTES] 만큼 뒤로 민다.
     * 오늘 계획일 때만 민다 (지난 날짜 퀴즈를 나중에 풀면 밀지 않음). 학원 같은 불가능 시간과 밀지 않는 태스크는 건너뛴다.
     * 트랜잭션 안에서 호출한다.
     */
    private fun shiftRemainingTasksForRetry(planTaskId: Int): List<PlanTaskResponse> {
        val failedTask = PlanTaskRow[planTaskId]
        val dailyPlan = failedTask.dailyPlan
        if (dailyPlan.planDate != todayInAppZone(clock)) return emptyList()

        val now = LocalTime.now(clock.withZone(APP_ZONE))
        val nowMinutes = now.hour * 60 + now.minute
        val dayTasks = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlan.id }.toList()
        val (toShift, stay) = dayTasks.partition {
            it.id.value != planTaskId && !it.isCompleted && PlanTaskScheduler.toMinutes(it.startTime) >= nowMinutes
        }
        if (toShift.isEmpty()) return emptyList()

        val weekday = dailyPlan.planDate.dayOfWeek.value
        val unavailable = UnavailableTimeRow.find { UnavailableTimeTable.user eq dailyPlan.planBoard.user.id }
            .filter { it.dayOfWeek.code.toInt() == weekday }
            .map { PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime) }
        val stayRanges = stay.map { PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime) }

        val shifted = shiftLaterTasks(
            toShift.map { ShiftableTask(it.id.value, PlanTaskScheduler.toMinutes(it.startTime), PlanTaskScheduler.toMinutes(it.endTime)) },
            QUIZ_FAIL_SHIFT_MINUTES,
            unavailable + stayRanges
        )
        return shifted.map { moved ->
            val row = PlanTaskRow[moved.id]
            row.startTime = moved.startTime
            row.endTime = moved.endTime
            PlanTaskResponse(
                id = row.id.value,
                dailyPlanId = dailyPlan.id.value,
                taskName = row.taskName,
                startTime = row.startTime.toString(),
                endTime = row.endTime.toString(),
                estimatedMinutes = row.estimatedMinutes,
                isCompleted = row.isCompleted
            )
        }
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
