package com.github.nepyh.rooter.module.quiz

import com.github.nepyh.rooter.common.QUIZ_CHAPTER_NAME_MAX
import com.github.nepyh.rooter.common.matchQuizChapter
import com.github.nepyh.rooter.common.quizQuestionCount
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.orderedChaptersInRange
import com.github.nepyh.rooter.module.planboard.planBoardSubjects
import com.github.nepyh.rooter.module.planboard.PlanTaskScheduler
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
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
import com.github.nepyh.rooter.module.quiz.dto.QuizQuestionResult
import com.github.nepyh.rooter.module.quiz.dto.QuizResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizResultResponse
import com.github.nepyh.rooter.module.quiz.dto.QuizSubjectResponse
import com.github.nepyh.rooter.module.quiz.dto.WeakAreaSummary
import com.github.nepyh.rooter.module.review.ReviewChoiceDraft
import com.github.nepyh.rooter.module.review.addCustomReviewQuestion
import com.github.nepyh.rooter.module.review.copyWrongQuestionToReview
import com.github.nepyh.rooter.module.review.model.ReviewSource
import com.github.nepyh.rooter.module.quiz.exception.QuizNotFoundException
import com.github.nepyh.rooter.module.quiz.exception.QuizValidationException
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizAttemptTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizChoiceTable
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionRow
import com.github.nepyh.rooter.module.quiz.model.DailyQuizQuestionTable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.random.Random

private const val REVIEW_TASK_MINUTES = 20
private const val CUSTOM_QUESTIONS_PER_CHAPTER = 2 // 약점 단원마다 복습 목록에 넣을 맞춤 문제 수

/** generateQuiz 의 첫 트랜잭션 결과 — 이미 있는 퀴즈를 돌려줄지, LLM 으로 새로 만들지 */
private sealed interface QuizPreparation {
    data class Existing(val quiz: QuizResponse) : QuizPreparation
    data class New(val dailyPlanId: Int, val context: String, val questionCount: Int, val chapterNames: List<String>) : QuizPreparation
}

class QuizService(
    private val llmClient: QuizLlmClient,
    private val schoolDataFetcher: SchoolDataFetcher,
    private val random: Random = Random.Default // 보기 섞기용. 테스트에서 시드를 고정한다
) {

    /**
     * 그날의 일일 퀴즈를 만든다. 이미 만들어진 퀴즈가 있으면 새로 만들지 않고 그대로 돌려준다
     * (앱이 태스크를 완료할 때마다 불러도 문제가 계속 쌓이지 않게).
     *
     * LLM 호출(수 초)은 DB 트랜잭션 밖에서 하고, 저장할 때 일일 계획 행을 잠근 뒤 한 번 더 확인해서
     * 동시에 두 번 요청돼도 한 세트만 저장되게 한다.
     */
    suspend fun generateQuiz(userId: Int, date: LocalDate, requestedPlanBoardId: Int? = null): QuizResponse {
        val prepared = newSuspendedTransaction {
            // 소유자 확인을 위해 daily_plans + plan_boards 를 조인해야 해서 Table DSL 을 쓴다 (집계/조인은 DAO 대상 아님).
            // 같은 날 플랜보드가 여러 개일 수 있어서 requestedPlanBoardId 를 받으면 그 보드로 한정한다.
            // 남의 보드·없는 보드면 후보가 비어 NoPlanForDateException 이 된다.
            val candidates = (DailyPlanTable innerJoin PlanBoardTable)
                .selectAll()
                .where {
                    val mine = (DailyPlanTable.planDate eq date) and (PlanBoardTable.userId eq userId)
                    if (requestedPlanBoardId == null) mine else mine and (PlanBoardTable.id eq requestedPlanBoardId)
                }
                .orderBy(PlanBoardTable.id to SortOrder.ASC)
                .toList()
            // 보드를 지정하지 않은 요청(예전 클라이언트)용: 학습 범위가 있는 보드를 우선한다
            // (앱이 자동으로 만드는 범위 없는 '기본 플랜보드' 로 퀴즈가 만들어지지 않게)
            val boardsWithRange = PlanSubjectTable.selectAll()
                .where { PlanSubjectTable.planBoardId inList candidates.map { it[DailyPlanTable.planBoardId] } }
                .map { it[PlanSubjectTable.planBoardId].value }
                .toSet()
            val dailyPlanRow = candidates.firstOrNull { it[DailyPlanTable.planBoardId].value in boardsWithRange }
                ?: candidates.firstOrNull()
                ?: throw QuizValidationException.NoPlanForDateException()

            val dailyPlanId = dailyPlanRow[DailyPlanTable.id].value
            existingQuiz(dailyPlanId, date)?.let { return@newSuspendedTransaction QuizPreparation.Existing(it) }

            val planBoardId = dailyPlanRow[DailyPlanTable.planBoardId].value
            val chapterNames = chapterNamesForPlanBoard(planBoardId)
            val completedTasks = PlanTaskRow.find {
                (PlanTaskTable.dailyPlanId eq dailyPlanId) and (PlanTaskTable.status eq PlanTaskStatus.DONE)
            }.toList()
            val completedTaskNames = completedTasks.map { it.taskName }

            QuizPreparation.New(
                dailyPlanId = dailyPlanId,
                context = buildString {
                    appendLine("학습 범위: ${chapterNames.joinToString(", ").ifBlank { "지정 안 됨" }}")
                    appendLine("오늘 완료한 학습: ${completedTaskNames.joinToString(", ").ifBlank { "없음" }}")
                },
                // 그날 공부한 시간 = 완료한 할일들의 예상 시간 합
                questionCount = quizQuestionCount(completedTasks.sumOf { it.estimatedMinutes }),
                chapterNames = chapterNames
            )
        }

        val (dailyPlanId, context, questionCount, chapterNames) = when (prepared) {
            is QuizPreparation.Existing -> return prepared.quiz
            is QuizPreparation.New -> prepared
        }

        // AI 가 더 많이 줘도 정한 문항 수까지만 쓴다
        val generated = llmClient.generateQuestions(context, questionCount).take(questionCount)
        if (generated.isEmpty()) throw QuizValidationException.QuizGenerationFailedException()

        return newSuspendedTransaction {
            // 같은 일일 계획에 대한 동시 생성 요청을 직렬화한다 — 먼저 저장한 쪽의 퀴즈를 뒤 요청도 그대로 받는다
            DailyPlanTable.selectAll().where { DailyPlanTable.id eq dailyPlanId }.forUpdate().single()
            existingQuiz(dailyPlanId, date)?.let { return@newSuspendedTransaction it }

            val questions = generated.map { question ->
                val quizQuestion = DailyQuizQuestionRow.new {
                    this.dailyPlan = DailyPlanRow[dailyPlanId]
                    questionText = question.questionText
                    explanation = question.explanation.ifBlank { null }
                    chapterName = matchQuizChapter(question.chapterName, chapterNames)
                }

                // AI 는 정답을 앞쪽 보기에 두는 경향이 있어, 저장 전에 보기 순서를 섞는다 (정답 표시는 보기와 함께 움직인다)
                val choices = question.choices.indices.shuffled(random).map { index ->
                    val choiceText = question.choices[index]
                    val choice = DailyQuizChoiceRow.new {
                        this.question = quizQuestion
                        this.choiceText = choiceText
                        isCorrect = index == question.correctIndex
                    }
                    QuizChoiceResponse(id = choice.id.value, choiceText = choiceText)
                }

                QuizQuestionResponse(id = quizQuestion.id.value, questionText = question.questionText, choices = choices)
            }

            QuizResponse(dailyPlanId = dailyPlanId, quizDate = date.toString(), questions = questions, subjects = subjectsOf(dailyPlanId))
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
        return if (questions.isEmpty()) {
            null
        } else {
            QuizResponse(dailyPlanId = dailyPlanId, quizDate = quizDate.toString(), questions = questions, subjects = subjectsOf(dailyPlanId))
        }
    }

    /** 일일 계획이 속한 플랜보드의 학습 범위 과목. 트랜잭션 안에서 호출한다. */
    private fun subjectsOf(dailyPlanId: Int): List<QuizSubjectResponse> =
        planBoardSubjects(DailyPlanRow[dailyPlanId].planBoard.id.value)
            .map { (id, name) -> QuizSubjectResponse(subjectId = id, subjectName = name) }

    suspend fun getQuiz(userId: Int, dailyPlanId: Int): QuizResponse = newSuspendedTransaction {
        // 소유자 확인용 조인 조회 (위 generateQuiz 와 같은 이유로 Table DSL 유지)
        val dailyPlanRow = (DailyPlanTable innerJoin PlanBoardTable)
            .selectAll()
            .where { (DailyPlanTable.id eq dailyPlanId) and (PlanBoardTable.userId eq userId) }
            .firstOrNull()
            ?: throw QuizNotFoundException()

        existingQuiz(dailyPlanId, dailyPlanRow[DailyPlanTable.planDate]) ?: throw QuizNotFoundException()
    }

    /**
     * 퀴즈를 채점해 저장하고, 틀린 문제가 있으면 AI 가 뽑은 약점 단원마다 복습 태스크를 계획에 추가한다.
     *
     * - 채점·제출 기록은 먼저 저장해 끝낸다. 약점 분석(LLM)이 실패해도 제출은 성공하고 weakAreas 만 비어서 나간다.
     * - 복습 태스크는 퀴즈 다음 날(플랜보드 기간을 넘으면 퀴즈 당일)에, 학교·수면·불가능 시간과
     *   그날 이미 있는 태스크를 피해서 배치한다. 빈 시간이 없어 못 넣은 제안은 insertedReviewTasks 에서 빠진다.
     * - 약점 단원마다 맞춤 문제를 [CUSTOM_QUESTIONS_PER_CHAPTER]개씩 만들어 복습 목록에 넣는다.
     *   복습 태스크 배치와 동시에 돌리고, 만들기에 실패해도 제출은 성공한다 (customReviewQuestionCount 만 0).
     */
    suspend fun submitQuiz(
        userId: Int,
        dailyPlanId: Int,
        answers: List<QuizAnswerSubmission>
    ): QuizResultResponse {
        val graded = newSuspendedTransaction {
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
            val results = mutableListOf<QuizQuestionResult>()

            for (answer in answers) {
                val choiceRow = DailyQuizChoiceRow.find {
                    (DailyQuizChoiceTable.id eq answer.selectedChoiceId) and (DailyQuizChoiceTable.questionId eq answer.questionId)
                }.firstOrNull() ?: throw QuizValidationException.InvalidAnswerException()

                DailyQuizAttemptRow.new {
                    this.userId = userId
                    selectedChoice = choiceRow
                    createdAt = OffsetDateTime.now()
                }

                val question = DailyQuizQuestionRow[answer.questionId]
                val questionChoices = DailyQuizChoiceRow.find { DailyQuizChoiceTable.questionId eq answer.questionId }.toList()
                if (choiceRow.isCorrect) {
                    correctCount++
                } else {
                    wrongQuestionTexts.add(question.questionText)
                    // 틀린 문제는 복습 목록으로 (일일 퀴즈는 보기별 이유가 없어 풀이만 옮긴다)
                    copyWrongQuestionToReview(
                        userId = userId,
                        source = ReviewSource.DAILY_QUIZ,
                        sourceQuestionId = answer.questionId,
                        chapterName = question.chapterName,
                        questionText = question.questionText,
                        explanation = question.explanation,
                        choices = questionChoices.map { ReviewChoiceDraft(it.choiceText, it.isCorrect) }
                    )
                }
                val correctChoice = questionChoices.firstOrNull { it.isCorrect }
                results += QuizQuestionResult(
                    questionId = answer.questionId,
                    questionText = question.questionText,
                    selectedChoiceId = choiceRow.id.value,
                    correctChoiceId = correctChoice?.id?.value,
                    correctChoiceText = correctChoice?.choiceText,
                    isCorrect = choiceRow.isCorrect,
                    explanation = question.explanation
                )
            }

            GradedQuiz(
                planBoardId = planBoardId,
                reviewDate = reviewDateFor(planBoardId, quizDate),
                correctCount = correctCount,
                wrongQuestionTexts = wrongQuestionTexts,
                results = results,
                chapterNames = if (wrongQuestionTexts.isEmpty()) emptyList() else chapterNamesForPlanBoard(planBoardId),
                scheduleContext = scheduleContextOf(userId)
            )
        }

        val suggestions = if (graded.wrongQuestionTexts.isEmpty()) {
            emptyList()
        } else {
            // 약점 분석 실패는 제출 실패로 만들지 않는다 (채점 결과는 이미 저장됨)
            runCatching { llmClient.analyzeWeakAreas(graded.wrongQuestionTexts, graded.chapterNames) }
                .getOrDefault(emptyList())
        }

        val weakAreas = suggestions.map {
            WeakAreaSummary(chapterName = it.chapterName, reviewTaskDescription = it.reviewTaskDescription)
        }
        val (insertedReviewTasks, customReviewQuestionCount) = coroutineScope {
            val customCount = async { addCustomReviewQuestions(userId, graded, suggestions) }
            val tasks = if (suggestions.isEmpty()) {
                emptyList()
            } else {
                insertReviewTasks(graded, suggestions.map { "복습: ${it.reviewTaskDescription}".take(150) })
            }
            tasks to customCount.await()
        }

        return QuizResultResponse(
            totalQuestions = answers.size,
            correctCount = graded.correctCount,
            weakAreas = weakAreas,
            insertedReviewTasks = insertedReviewTasks,
            customReviewQuestionCount = customReviewQuestionCount,
            results = graded.results
        )
    }

    /** 복습 태스크를 넣을 날짜 — 퀴즈 다음 날, 그날이 플랜보드 기간을 넘으면 퀴즈 당일. 트랜잭션 안에서 호출한다. */
    private fun reviewDateFor(planBoardId: Int, quizDate: LocalDate): LocalDate {
        val nextDate = quizDate.plusDays(1)
        return if (nextDate.isAfter(PlanBoardRow[planBoardId].endDate)) quizDate else nextDate
    }

    private fun scheduleContextOf(userId: Int): ReviewScheduleContext {
        val profileRow = StudentProfileRow.find { StudentProfileTable.user eq userId }.firstOrNull()
        return ReviewScheduleContext(
            grade = profileRow?.grade ?: 2,
            schoolId = profileRow?.schoolId,
            classNumber = profileRow?.classNumber,
            customUnavailableRows = UnavailableTimeRow.find { UnavailableTimeTable.user eq userId }.map {
                it.dayOfWeek.code.toInt() to
                    (PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime))
            }
        )
    }

    private suspend fun insertReviewTasks(graded: GradedQuiz, taskNames: List<String>): List<InsertedReviewTaskResponse> {
        val date = graded.reviewDate
        val ctx = graded.scheduleContext
        // NICE 시간표 조회(네트워크)가 있어 트랜잭션 밖에서 계산해둔다
        val unavailable = PlanTaskScheduler.buildUnavailableRanges(
            schoolDataFetcher = schoolDataFetcher,
            startDate = date,
            endDate = date,
            schoolId = ctx.schoolId,
            classNumber = ctx.classNumber,
            grade = ctx.grade,
            customRows = ctx.customUnavailableRows
        )[date].orEmpty()

        return newSuspendedTransaction {
            val dailyPlan = DailyPlanRow.find {
                (DailyPlanTable.planBoardId eq graded.planBoardId) and (DailyPlanTable.planDate eq date)
            }.firstOrNull() ?: DailyPlanRow.new {
                planBoard = PlanBoardRow[graded.planBoardId]
                planDate = date
            }

            val existingTaskRanges = PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlan.id }.map {
                PlanTaskScheduler.toMinutes(it.startTime) to PlanTaskScheduler.toMinutes(it.endTime)
            }
            val placed = PlanTaskScheduler.placeTasks(
                taskNames.map { it to REVIEW_TASK_MINUTES },
                PlanTaskScheduler.freeIntervalsFromBusyRanges(unavailable + existingTaskRanges)
            )

            placed.map { task ->
                PlanTaskRow.new {
                    this.dailyPlan = dailyPlan
                    taskName = task.taskName
                    startTime = task.startTime
                    endTime = task.endTime
                    estimatedMinutes = task.estimatedMinutes
                }
                InsertedReviewTaskResponse(
                    dailyPlanId = dailyPlan.id.value,
                    planDate = date.toString(),
                    taskName = task.taskName,
                    startTime = task.startTime.toString(),
                    endTime = task.endTime.toString()
                )
            }
        }
    }

    /**
     * 약점 단원 맞춤 문제를 AI 로 만들어 복습 목록에 넣고, 넣은 개수를 돌려준다. AI 실패는 0 으로 넘긴다.
     * AI 가 약점 단원이 아닌 이름을 쓰거나 단원당 수를 넘기거나 정답 위치가 잘못된 문제는 버린다
     * (chapter_name 은 단원별 오답률 리포트의 기준이라 약점 단원 이름 그대로여야 한다).
     */
    private suspend fun addCustomReviewQuestions(userId: Int, graded: GradedQuiz, suggestions: List<WeakAreaSuggestion>): Int {
        val weakChapterNames = suggestions.map { it.chapterName.trim().take(QUIZ_CHAPTER_NAME_MAX) }.filter { it.isNotEmpty() }.distinct()
        if (weakChapterNames.isEmpty()) return 0

        val generated = runCatching {
            llmClient.generateCustomReviewQuestions(
                weakChapterNames, graded.wrongQuestionTexts, graded.scheduleContext.grade, CUSTOM_QUESTIONS_PER_CHAPTER
            )
        }.getOrElse { return 0 }

        val accepted = generated
            .map { it.copy(chapterName = it.chapterName.trim().take(QUIZ_CHAPTER_NAME_MAX)) }
            .filter { it.chapterName in weakChapterNames && it.questionText.isNotBlank() && it.choices.size >= 2 && it.correctIndex in it.choices.indices }
            .groupBy { it.chapterName }
            .values
            .flatMap { it.take(CUSTOM_QUESTIONS_PER_CHAPTER) }
        if (accepted.isEmpty()) return 0

        return newSuspendedTransaction {
            accepted.count { question ->
                addCustomReviewQuestion(
                    userId = userId,
                    chapterName = question.chapterName,
                    questionText = question.questionText,
                    explanation = question.explanation,
                    choices = question.choices.mapIndexed { index, text ->
                        ReviewChoiceDraft(text.take(200), index == question.correctIndex, question.choiceReasons.getOrNull(index))
                    }
                )
            }
        }
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
}

/** submitQuiz 에서 채점 트랜잭션이 끝난 뒤 복습 태스크 배치에 넘기는 값 */
private data class GradedQuiz(
    val planBoardId: Int,
    val reviewDate: LocalDate,
    val correctCount: Int,
    val wrongQuestionTexts: List<String>,
    val results: List<QuizQuestionResult>,
    val chapterNames: List<String>,
    val scheduleContext: ReviewScheduleContext
)

private data class ReviewScheduleContext(
    val grade: Int,
    val schoolId: String?,
    val classNumber: Int?,
    val customUnavailableRows: List<Pair<Int, Pair<Int, Int>>>
)
