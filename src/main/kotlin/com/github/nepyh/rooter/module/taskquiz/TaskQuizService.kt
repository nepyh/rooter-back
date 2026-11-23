package com.github.nepyh.rooter.module.taskquiz

import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.common.isReachedInAppZone
import com.github.nepyh.rooter.common.matchQuizChapter
import com.github.nepyh.rooter.common.quizQuestionCount
import com.github.nepyh.rooter.common.todayInAppZone
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.planboard.userRoutine
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskResponse
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.review.ReviewChoiceDraft
import com.github.nepyh.rooter.module.review.copyWrongQuestionToReview
import com.github.nepyh.rooter.module.review.model.ReviewSource
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskChapterTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.guessTaskSubject
import com.github.nepyh.rooter.module.planboard.orderedChaptersInRange
import com.github.nepyh.rooter.module.planboard.planBoardSubjects
import com.github.nepyh.rooter.module.planboard.isManualPlanBoard
import com.github.nepyh.rooter.module.planboard.subjectNameFrom
import com.github.nepyh.rooter.module.planboard.subjectNamesOf
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizAnswerResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizChoiceResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizQuestionResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizQuestionResult
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizResponse
import com.github.nepyh.rooter.module.taskquiz.dto.TaskQuizSubjectResponse
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
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.random.Random

const val MAX_ATTEMPTS = 3 // 최초 1회 + 재시도 2회
const val PASS_PERCENT = 70 // 70점 이상(문항의 70% 이상 정답)이면 통과 (4문항 3개, 5문항 4개, 7문항 5개)
const val RETRY_DELAY_MINUTES = 10L

/** 통과에 필요한 정답 수. AI 가 문항을 덜 줘서 저장된 문항 수가 적어도 그 수 기준으로 계산한다 */
fun taskQuizPassCount(totalCount: Int): Int = (totalCount * PASS_PERCENT + 99) / 100

/** 퀴즈 출제에 쓸 학년 표시, 학습 범위(과목·단원), 문항 수, 문항에 붙일 수 있는 단원 이름 */
private data class TaskQuizContext(val gradeLabel: String, val studyScope: String, val questionCount: Int, val chapterNames: List<String>)

class TaskQuizService(
    private val llmClient: TaskQuizLlmClient,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default // 보기 섞기용. 테스트에서 시드를 고정한다
) {

    /** 스케줄러가 호출. taskName은 findDue 시점에 이미 조회해둔 값을 그대로 받는다. */
    /**
     * attemptNumber 차수 퀴즈를 만든다. 만들었거나 이미 있으면 true, AI 생성 실패면 false.
     * 스케줄러(종료 시각)와 퀴즈 열기([openQuiz])가 동시에 부를 수 있어, 저장할 때 태스크 행을 잠그고 같은 차수가 있으면 만들지 않는다.
     */
    suspend fun generateAttempt(planTaskId: Int, attemptNumber: Int, taskName: String): Boolean {
        val (gradeLabel, studyScope, questionCount, chapterNames) = newSuspendedTransaction { quizContextOf(planTaskId) }
        // 정답 번호가 보기 범위를 벗어난 문제는 채점할 수 없으니 버리고, AI 가 더 많이 줘도 정한 문항 수까지만 쓴다
        val generated = llmClient.generateQuestions(taskName, gradeLabel, studyScope, questionCount)
            .filter { it.choices.size >= 2 && it.correct_index in it.choices.indices }
            .take(questionCount)
        if (generated.isEmpty()) return false // AI 생성 실패 시 이번 attempt는 건너뜀 (다음 스케줄 대상이 되진 않음)

        newSuspendedTransaction {
            PlanTaskTable.selectAll().where { PlanTaskTable.id eq planTaskId }.forUpdate().single()
            val exists = !TaskQuizAttemptRow.find {
                (TaskQuizAttemptTable.planTaskId eq planTaskId) and (TaskQuizAttemptTable.attemptNumber eq attemptNumber)
            }.empty()
            if (exists) return@newSuspendedTransaction

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
                    chapterName = matchQuizChapter(question.chapter_name, chapterNames)
                }

                // AI 는 정답을 앞쪽 보기에 두는 경향이 있어, 저장 전에 보기 순서를 섞는다 (정답 표시·짧은 이유는 보기와 함께 움직인다)
                question.choices.indices.shuffled(random).forEach { index ->
                    val choiceText = question.choices[index]
                    TaskQuizChoiceRow.new {
                        this.question = quizQuestion
                        this.choiceText = choiceText
                        isCorrect = index == question.correct_index
                        // 정답 보기에는 자세한 풀이를, 오답 보기에는 그 보기를 골랐을 때 바로 보여줄 짧은 이유를 둔다.
                        // 짧은 이유가 없으면 풀이로 대신한다
                        explanation = if (isCorrect) {
                            question.explanation
                        } else {
                            question.choice_reasons.getOrNull(index)?.takeIf { it.isNotBlank() } ?: question.explanation
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * 퀴즈 열기 (GET /plan-tasks/{taskId}/quiz). 앱은 완료 버튼을 누르면 이걸 부른다.
     * 종료 시각을 기다리지 않고 풀 퀴즈를 돌려준다 — 퀴즈를 통과해야 태스크가 완료된다.
     * - 아직 퀴즈가 없으면 1차를 지금 만든다 (예전엔 종료 시각 전이면 404 였음)
     * - 직전 차수에서 떨어졌고 재시도 대기(RETRY_DELAY_MINUTES)가 지났으면 스케줄러를 기다리지 않고 다음 차수를 만든다
     * - 그 외(풀던 퀴즈, 대기 중, 통과, 3회 소진)는 예전처럼 가장 최근 차수를 그대로 돌려준다
     * - 할일 시작 시각(계획 날짜 + startTime) 전이면 퀴즈를 만들지 않고 TASK_NOT_STARTED (#353) — 공부하기 전에 미리 풀어 완료하지 못하게
     */
    suspend fun openQuiz(userId: Int, planTaskId: Int): TaskQuizResponse {
        val (taskName, latest) = newSuspendedTransaction {
            requireOwnedTask(userId, planTaskId)
            requireStartedTask(planTaskId)
            val latest = TaskQuizAttemptRow.find { TaskQuizAttemptTable.planTaskId eq planTaskId }
                .orderBy(TaskQuizAttemptTable.attemptNumber to SortOrder.DESC)
                .firstOrNull()
                ?.let { Triple(it.attemptNumber, it.passed, it.createdAt) }
            PlanTaskRow[planTaskId].taskName to latest
        }

        val nextAttempt = when {
            latest == null -> 1
            latest.second == false && latest.first < MAX_ATTEMPTS &&
                !latest.third.plusMinutes(RETRY_DELAY_MINUTES).isAfter(OffsetDateTime.now(clock)) -> latest.first + 1
            else -> null
        }
        if (nextAttempt != null && !generateAttempt(planTaskId, nextAttempt, taskName)) {
            throw TaskQuizValidationException.GenerationFailedException()
        }
        return getCurrentQuiz(userId, planTaskId)
    }

    fun getCurrentQuiz(userId: Int, planTaskId: Int): TaskQuizResponse = transaction {
        requireOwnedTask(userId, planTaskId)

        val latestAttempt = latestAttemptOrThrow(planTaskId)

        // 답을 저장(UPDATE)하면 PostgreSQL 의 행 순서가 바뀌므로, 문제·보기 순서는 id 로 고정한다
        val questions = TaskQuizQuestionRow.find { TaskQuizQuestionTable.attemptId eq latestAttempt.id }
            .orderBy(TaskQuizQuestionTable.id to SortOrder.ASC)
            .map { question ->
                val choices = TaskQuizChoiceRow.find { TaskQuizChoiceTable.questionId eq question.id }
                    .orderBy(TaskQuizChoiceTable.id to SortOrder.ASC)
                    .map { TaskQuizChoiceResponse(id = it.id.value, choiceText = it.choiceText) }

                TaskQuizQuestionResponse(
                    id = question.id.value,
                    questionText = question.questionText,
                    choices = choices,
                    selectedChoiceId = question.selectedChoiceId
                )
            }

        val task = PlanTaskRow[planTaskId]
        val subjects = planBoardSubjects(task.dailyPlan.planBoard.id.value)

        TaskQuizResponse(
            planTaskId = planTaskId,
            attemptNumber = latestAttempt.attemptNumber,
            questions = questions,
            passCount = taskQuizPassCount(latestAttempt.totalCount),
            subject = guessTaskSubject(task.taskName, subjects)?.let { (id, name) -> TaskQuizSubjectResponse(id, name) },
            subjects = subjects.map { (id, name) -> TaskQuizSubjectResponse(id, name) }
        )
    }

    /**
     * 문제 하나를 풀 때마다 즉시 호출. 답을 DB 에 저장하고 바로 채점 결과를 돌려준다.
     * 이미 답한 문제는 다시 답할 수 없다 (정답을 본 뒤 답을 바꿔치기하는 것을 막기 위함).
     */
    fun answerQuestion(userId: Int, planTaskId: Int, questionId: Int, selectedChoiceId: Int): TaskQuizAnswerResponse = transaction {
        requireOwnedTask(userId, planTaskId)
        // 시작 전에 만들어진 퀴즈라도 시작 전에 풀면 정답·풀이가 미리 보이고 오답이 복습으로 복사된다 (#353)
        requireStartedTask(planTaskId)

        val attempt = latestAttemptOrThrow(planTaskId)
        if (attempt.passed != null) {
            throw TaskQuizValidationException.AlreadySubmittedException()
        }

        val question = TaskQuizQuestionRow.findById(questionId)
            ?.takeIf { it.attempt.id == attempt.id }
            ?: throw TaskQuizNotFoundException()

        if (question.selectedChoiceId != null) {
            throw TaskQuizValidationException.AlreadyAnsweredException()
        }

        val choices = TaskQuizChoiceRow.find { TaskQuizChoiceTable.questionId eq question.id }.toList()
        val selectedChoice = choices.find { it.id.value == selectedChoiceId }
            ?: throw TaskQuizValidationException.InvalidAnswerException()
        // 생성 단계에서 정답 번호를 검증하기 전에 만든 퀴즈는 정답 보기가 없을 수 있어, 크래시내지 않도록 마지막 보기로 대체한다.
        val correctChoice = choices.find { it.isCorrect } ?: choices.last()

        // "다음"을 연달아 눌러 요청 두 개가 동시에 들어와도 먼저 저장된 답만 남도록, 아직 비어 있을 때만 저장한다
        val saved = TaskQuizQuestionTable.update({
            (TaskQuizQuestionTable.id eq question.id) and TaskQuizQuestionTable.selectedChoiceId.isNull()
        }) { it[TaskQuizQuestionTable.selectedChoiceId] = selectedChoiceId }
        if (saved == 0) throw TaskQuizValidationException.AlreadyAnsweredException()

        if (!selectedChoice.isCorrect) {
            // 틀린 문제는 복습 목록으로. 정답 보기의 explanation 이 자세한 풀이, 오답 보기의 explanation 이 짧은 이유다
            copyWrongQuestionToReview(
                userId = userId,
                source = ReviewSource.TASK_QUIZ,
                sourceQuestionId = question.id.value,
                chapterName = question.chapterName,
                questionText = question.questionText,
                explanation = correctChoice.explanation,
                choices = choices.map { ReviewChoiceDraft(it.choiceText, it.isCorrect, reason = it.explanation) }
            )
        }

        TaskQuizAnswerResponse(
            questionId = question.id.value,
            isCorrect = selectedChoice.isCorrect,
            correctChoiceId = correctChoice.id.value,
            // 오답 보기에는 그 보기가 왜 틀렸는지 짧은 이유가, 정답 보기에는 자세한 풀이가 들어 있다
            reason = if (selectedChoice.isCorrect) null else selectedChoice.explanation,
            explanation = correctChoice.explanation
        )
    }

    /** 채점은 항상 answerQuestion 으로 DB에 저장해 둔 답만 본다 — 클라이언트가 이 호출에 보내는 값은 없다. */
    fun submitQuiz(userId: Int, planTaskId: Int): TaskQuizSubmitResponse = transaction {
        requireOwnedTask(userId, planTaskId)
        // 시작 전에 열어 둔 퀴즈(이 검사 전 버전·시작 시간을 뒤로 옮긴 할일)를 시작 전에 제출하는 것도 막는다 (#353)
        requireStartedTask(planTaskId)

        val attempt = latestAttemptOrThrow(planTaskId)
        if (attempt.passed != null) {
            throw TaskQuizValidationException.AlreadySubmittedException()
        }

        val questions = TaskQuizQuestionRow.find { TaskQuizQuestionTable.attemptId eq attempt.id }
            .orderBy(TaskQuizQuestionTable.id to SortOrder.ASC)
            .toList()
        if (questions.any { it.selectedChoiceId == null }) {
            throw TaskQuizValidationException.IncompleteAnswersException()
        }

        val results = questions.map { question ->
            val choices = TaskQuizChoiceRow.find { TaskQuizChoiceTable.questionId eq question.id }.toList()
            // answerQuestion 에서 이미 이 문제의 보기로 검증된 값이라 여기서 못 찾는 건 데이터 정합성이 깨진 것
            val selectedChoice = choices.find { it.id.value == question.selectedChoiceId }
                ?: error("task_quiz_question ${question.id.value} 의 selected_choice_id 가 자신의 보기가 아님")
            val correctChoice = choices.find { it.isCorrect } ?: choices.last()

            TaskQuizQuestionResult(
                questionId = question.id.value,
                questionText = question.questionText,
                selectedChoiceId = selectedChoice.id.value,
                correctChoiceId = correctChoice.id.value,
                correctChoiceText = correctChoice.choiceText,
                isCorrect = selectedChoice.isCorrect,
                explanation = correctChoice.explanation
            )
        }

        val attemptNumber = attempt.attemptNumber
        val totalCount = attempt.totalCount
        val correctCount = results.count { it.isCorrect }
        val passCount = taskQuizPassCount(totalCount)
        val passed = correctCount >= passCount

        // 제출을 연달아 보내도 한 번만 채점·완료·계획 밀기가 일어나도록, 아직 채점 전일 때만 결과를 저장한다
        val graded = TaskQuizAttemptTable.update({
            (TaskQuizAttemptTable.id eq attempt.id) and TaskQuizAttemptTable.passed.isNull()
        }) {
            it[TaskQuizAttemptTable.correctCount] = correctCount
            it[TaskQuizAttemptTable.passed] = passed
            it[TaskQuizAttemptTable.submittedAt] = OffsetDateTime.now() // 퀴즈 히스토리의 완료 시간 (rooter-ddl #34)
        }
        if (graded == 0) throw TaskQuizValidationException.AlreadySubmittedException()

        var retryScheduled = false
        var taskInvalidated = false
        var shiftedTasks = emptyList<PlanTaskResponse>()

        if (passed) {
            // 퀴즈 통과 = 완료 확인 자체이므로 체크 여부와 무관하게 완료 처리. 그사이 실패 처리한 태스크는 실패로 둔다
            PlanTaskRow[planTaskId].takeUnless { it.status == PlanTaskStatus.FAILED }?.status = PlanTaskStatus.DONE
        } else if (attemptNumber < MAX_ATTEMPTS) {
            retryScheduled = true // 스케줄러가 RETRY_DELAY_MINUTES 뒤 다음 attempt를 자동 생성
            // 재시도 퀴즈를 풀 시간만큼 오늘 남은 계획을 뒤로 민다 (퀴즈와 다음 공부가 겹치지 않게)
            shiftedTasks = shiftRemainingTasksForRetry(planTaskId)
        } else {
            // 최초 1회 + 재시도 2회 모두 실패 -> 실패로 확정 (status=FAILED, 잔디에는 미완료로 반영됨)
            PlanTaskRow[planTaskId].status = PlanTaskStatus.FAILED
            taskInvalidated = true
        }

        TaskQuizSubmitResponse(
            attemptNumber = attemptNumber,
            correctCount = correctCount,
            totalCount = totalCount,
            passCount = passCount,
            passed = passed,
            retryScheduled = retryScheduled,
            taskInvalidated = taskInvalidated,
            results = results,
            shiftedTasks = shiftedTasks
        )
    }

    private fun latestAttemptOrThrow(planTaskId: Int): TaskQuizAttemptRow =
        TaskQuizAttemptRow.find { TaskQuizAttemptTable.planTaskId eq planTaskId }
            .orderBy(TaskQuizAttemptTable.attemptNumber to SortOrder.DESC)
            .firstOrNull() ?: throw TaskQuizNotFoundException()

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
            it.id.value != planTaskId && it.status == PlanTaskStatus.PENDING && PlanTaskScheduler.toMinutes(it.startTime) >= nowMinutes
        }
        if (toShift.isEmpty()) return emptyList()

        val weekday = dailyPlan.planDate.dayOfWeek.value
        // 사용자가 직접 등록한 시간(학원·식사 등)만 피한다. 식사 기본값까지 막으면 15분 밀기가 저녁 뒤로 1시간 넘게 튄다
        val unavailable = userRoutine(dailyPlan.planBoard.user.id.value).slots
            .filter { it.dayOfWeek == weekday }
            .map { it.start to it.end }
        val stayRanges = stay.map { PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime) }

        val shifted = shiftLaterTasks(
            toShift.map { ShiftableTask(it.id.value, PlanTaskScheduler.toMinutes(it.startTime), PlanTaskScheduler.toMinutes(it.endTime)) },
            QUIZ_FAIL_SHIFT_MINUTES,
            unavailable + stayRanges
        )
        val movedRows = shifted.map { moved ->
            PlanTaskRow[moved.id].apply {
                startTime = moved.startTime
                endTime = moved.endTime
            }
        }
        val subjectNames = subjectNamesOf(movedRows)
        return movedRows.map { row ->
            PlanTaskResponse(
                id = row.id.value,
                dailyPlanId = dailyPlan.id.value,
                planBoardId = dailyPlan.readValues[DailyPlanTable.planBoardId].value,
                isManual = isManualPlanBoard(dailyPlan.planBoard.examDate),
                taskName = row.taskName,
                startTime = row.startTime.toString(),
                endTime = row.endTime.toString(),
                estimatedMinutes = row.estimatedMinutes,
                status = row.status,
                subjectId = row.subjectId?.value,
                subjectName = row.subjectNameFrom(subjectNames)
            )
        }
    }

    /** 할일 시작 시각(계획 날짜 + startTime, 서비스 기준 시간대) 전이면 [TaskQuizValidationException.TaskNotStartedException]. 트랜잭션 안에서 부른다 */
    private fun requireStartedTask(planTaskId: Int) {
        val task = PlanTaskRow[planTaskId]
        if (!isReachedInAppZone(task.dailyPlan.planDate, task.startTime, clock)) {
            throw TaskQuizValidationException.TaskNotStartedException()
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
     * 퀴즈 출제에 쓸 학년 표시와 학습 범위(과목·단원), 할일 길이에 맞춘 문항 수. 사용자가 직접 추가한 태스크는 이름만 있어서
     * ("영어 단어 30개 외우기" 등) 학년·범위가 없으면 AI 가 "학생이 완료한 활동은?" 같은 문제를 냈다.
     *
     * 학습 범위에는 **그 할일의 과목 하나만** 넣는다 (#387 — 국어 할일에 수학 문제).
     * - 할일에 단원이 저장돼 있으면: "수학: 소인수분해, 정수와 유리수" (그 단원만 — 보드 범위의 다른 단원은 넣지 않음)
     * - 과목을 알고 보드 범위에 그 과목이 있으면: "수학: 이차방정식, …" (보드 범위의 단원)
     * - 과목은 아는데 보드 범위에 없으면(직접 만든 일정 보드, 보드에 없는 교과서를 고른 할일): "과학: 단원 지정 안 됨"
     *   — 예전엔 여기서 보드의 모든 과목을 넘겨 다른 과목 문제가 나왔고, 범위가 비면 과목 이름조차 AI 에 가지 않았다
     * - 과목을 모르면: "지정 안 됨" — 보드의 다른 과목을 섞지 않고 AI 가 할일 이름으로만 낸다
     */
    private fun quizContextOf(planTaskId: Int): TaskQuizContext {
        val task = PlanTaskRow.findById(planTaskId)
            ?: return TaskQuizContext("중학생(학년 정보 없음)", NO_SCOPE, quizQuestionCount(0), emptyList())
        val questionCount = quizQuestionCount(task.estimatedMinutes)
        val board = task.dailyPlan.planBoard

        val grade = StudentProfileRow.find { StudentProfileTable.user eq board.user.id }.firstOrNull()?.grade
        val gradeLabel = grade?.let { "중학교 ${it}학년" } ?: "중학생(학년 정보 없음)"

        val planSubjects = PlanSubjectRow.find { PlanSubjectTable.planBoardId eq board.id }.toList()
        val boardSubjects = planSubjects.map { it.textbook.subject.id.value to it.textbook.subject.name }.distinctBy { it.first }
        val (subjectId, subjectName) = resolveTaskSubject(task, boardSubjects)
            ?: return TaskQuizContext(gradeLabel, NO_SCOPE, questionCount, emptyList())

        // 할일에 단원이 저장돼 있으면(앱에서 고른 단원, AI 계획의 할일별 단원) 보드 범위 대신 그 단원에서만 낸다 (#387)
        val taskChapterNames = taskChapterNamesOf(planTaskId)
        if (taskChapterNames.isNotEmpty()) {
            return TaskQuizContext(gradeLabel, "$subjectName: ${taskChapterNames.joinToString(", ")}", questionCount, taskChapterNames)
        }

        val chapterNames = mutableListOf<String>()
        val studyScope = planSubjects.filter { it.textbook.subject.id.value == subjectId }.joinToString("\n") { subject ->
            val textbookChapters = orderedChaptersInRange(subject.textbook, subject.startChapter, subject.endChapter).map { it.chapterName }
            chapterNames += textbookChapters // 직접 적은 범위(customRangeText)는 단원이 아니라 리포트 기준에서 뺀다
            val chapters = textbookChapters + listOfNotNull(subject.customRangeText?.takeIf { it.isNotBlank() })
            "${subject.textbook.subject.name}: ${chapters.joinToString(", ").ifBlank { NO_CHAPTERS }}"
        }.ifBlank { "$subjectName: $NO_CHAPTERS" }

        return TaskQuizContext(gradeLabel, studyScope, questionCount, chapterNames)
    }

    /** 할일에 저장된 단원 이름 (plan_task_chapters). 단원 id 순 — 마스터 데이터가 교과서 순서로 들어가 있고, chapter_order 는 소단원에서 다시 1부터라 쓸 수 없다. 없으면 빈 목록 */
    private fun taskChapterNamesOf(planTaskId: Int): List<String> =
        (PlanTaskChapterTable innerJoin ChapterTable)
            .select(ChapterTable.chapterName)
            .where { PlanTaskChapterTable.planTaskId eq planTaskId }
            .orderBy(ChapterTable.id to SortOrder.ASC)
            .map { it[ChapterTable.chapterName] }
            .distinct()

    /**
     * 할일 과목 (id, 이름). 순서대로 본다.
     * 1. 저장된 subject_id (AI 계획·교과서를 고른 직접 추가·챗봇 수정·복습 할일)
     * 2. 할일 이름으로 보드 과목 중에서 추정 ([guessTaskSubject] — 이름에 과목명이 하나, 또는 보드 과목이 하나)
     * 3. 할일 이름에 든 과목명으로 전체 과목 중에서 추정 — 직접 만든 일정 보드처럼 보드 범위가 없거나, 보드에 없는 과목의 할일
     * 그래도 모르면 null.
     */
    private fun resolveTaskSubject(task: PlanTaskRow, boardSubjects: List<Pair<Int, String>>): Pair<Int, String>? {
        task.subjectId?.let { id -> SubjectRow.findById(id)?.let { return id.value to it.name } }
        guessTaskSubject(task.taskName, boardSubjects)?.let { return it }
        return SubjectRow.all().map { it.id.value to it.name }
            .filter { (_, name) -> task.taskName.contains(name) }
            .singleOrNull()
    }

    private companion object {
        /** 과목을 모를 때의 학습 범위 */
        const val NO_SCOPE = "지정 안 됨"

        /** 과목은 알지만 단원 범위가 없을 때 과목 이름 뒤에 붙인다 (프롬프트 task-quiz-generation.md 와 같은 문구) */
        const val NO_CHAPTERS = "단원 지정 안 됨"
    }

}
