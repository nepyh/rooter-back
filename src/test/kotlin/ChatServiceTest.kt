import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.chat.ChatLlmClient
import com.github.nepyh.rooter.module.chat.ChatService
import com.github.nepyh.rooter.module.chat.PLAN_NOT_APPLIED_REPLY
import com.github.nepyh.rooter.module.chat.POSTPONE_NOTHING_REPLY
import com.github.nepyh.rooter.module.chat.dto.AiChatBusyDay
import com.github.nepyh.rooter.module.chat.dto.AiChatEdit
import com.github.nepyh.rooter.module.chat.dto.AiChatPlanUpdate
import com.github.nepyh.rooter.module.chat.dto.AiChatPostpone
import com.github.nepyh.rooter.module.chat.dto.AiChatResult
import com.github.nepyh.rooter.module.chat.dto.AiChatTask
import com.github.nepyh.rooter.module.chat.model.ChatTurnRow
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.chat.planBoardSummaryOf
import com.github.nepyh.rooter.module.chat.postponeBlockedReply
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectRow
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskOrigin
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerRow
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * chat 서비스 통합 테스트 (로컬 PostgreSQL 필요). LLM 은 [FakeChatLlmClient] 로 고정 응답을 준다.
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class ChatServiceTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureTestDatabase(testDbUrl, testDbUser, testDbPassword)

    val db = Database.connect(
        url = testDbUrl,
        driver = "org.postgresql.Driver",
        user = testDbUser,
        password = testDbPassword
    )

    beforeSpec {
        transaction(db) {
            exec("DROP TABLE IF EXISTS chat_turns CASCADE")
            exec("DROP TABLE IF EXISTS plan_subjects CASCADE")
            exec("DROP TABLE IF EXISTS task_quiz_attempts CASCADE")
            exec("DROP TABLE IF EXISTS plan_tasks CASCADE")
            exec("DROP TABLE IF EXISTS daily_plans CASCADE")
            exec("DROP TABLE IF EXISTS plan_boards CASCADE")
            exec("DROP TABLE IF EXISTS study_style_answers CASCADE")
            exec("DROP TABLE IF EXISTS user_unavailable_times CASCADE")
            exec("DROP TABLE IF EXISTS student_profiles CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(
                UserTable, StudentProfileTable, UnavailableTimeTable, StudyStyleAnswerTable,
                PlanBoardTable, DailyPlanTable, PlanTaskTable, ChatTurnTable,
                // 할일 과목(plan_tasks.subject_id) FK 대상
                SubjectTable,
                // 할일 직접 편집: 플랜보드 과목·학습 범위, 할일 퀴즈 상태
                TextbookTable, ChapterTable, PlanSubjectTable, TaskQuizAttemptTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            ChatTurnTable.deleteAll()
            TaskQuizAttemptTable.deleteAll()
            PlanSubjectTable.deleteAll()
            PlanTaskTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            StudyStyleAnswerTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    // 프로필(schoolId) 이 없으면 NICE 를 호출하지 않으므로, 호출되면 바로 실패하게 둔다
    val schoolDataFetcher = SchoolDataFetcher(
        NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") }))
    )

    val tuesday = LocalDate.of(2026, 9, 29)
    // 계획 날짜(tuesday)가 '오늘' 이 아닌 시계 — 현재 시각 제한 없이 재배치 결과를 고정하기 위함
    val otherDayClock: Clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)

    fun seedDailyPlan(examDate: LocalDate? = null, boardEnd: LocalDate = tuesday): Pair<Int, Int> = transaction(db) {
        val user = UserRow.new {
            email = "chat@test.com"
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }
        val board = PlanBoardRow.new {
            this.user = user
            title = "테스트 보드"
            startDate = tuesday
            endDate = boardEnd
            this.examDate = examDate
            createdAt = OffsetDateTime.now()
        }
        val dailyPlan = DailyPlanRow.new {
            planBoard = board
            planDate = tuesday
        }
        user.id.value to dailyPlan.id.value
    }

    fun seedTask(
        dailyPlanId: Int, name: String, start: LocalTime, end: LocalTime, completed: Boolean,
        postponedFrom: LocalDate? = null, failed: Boolean = false, origin: PlanTaskOrigin = PlanTaskOrigin.USER
    ): Int = transaction(db) {
        PlanTaskRow.new {
            postponedFromDate = postponedFrom
            dailyPlan = DailyPlanRow[dailyPlanId]
            taskName = name
            startTime = start
            endTime = end
            this.origin = origin
            estimatedMinutes = (end.toSecondOfDay() - start.toSecondOfDay()) / 60
            status = when {
                failed -> PlanTaskStatus.FAILED
                completed -> PlanTaskStatus.DONE
                else -> PlanTaskStatus.PENDING
            }
        }.id.value
    }

    "채팅으로 계획을 재조정해도 이미 완료한 태스크는 그대로 남고, 새 태스크는 그 시간대를 피해 배치된다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        // 귀가(16:30~17:10)·저녁(18:00~19:00) 기본값과 안 겹치게 저녁 뒤에 둔다
        val doneId = seedTask(dailyPlanId, "완료한 태스크", LocalTime.of(19, 0), LocalTime.of(20, 0), completed = true)
        seedTask(dailyPlanId, "남은 태스크1", LocalTime.of(20, 10), LocalTime.of(21, 0), completed = false)
        seedTask(dailyPlanId, "남은 태스크2", LocalTime.of(21, 10), LocalTime.of(22, 0), completed = false)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "줄였어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("남은 태스크1", 40), AiChatTask("남은 태스크2", 40)))
            )
        )
        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "10분씩 줄여줘")

        // AI 에는 미완료 태스크만 전달된다
        llm.lastCurrentTasksJson!! shouldNotContain "완료한 태스크"

        // 완료 태스크는 id·시각·완료 상태가 그대로 유지된다
        transaction(db) {
            val done = PlanTaskRow.findById(doneId)!!
            done.status shouldBe PlanTaskStatus.DONE
            done.startTime shouldBe LocalTime.of(19, 0)
            done.endTime shouldBe LocalTime.of(20, 0)
            PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }.count() shouldBe 3L
        }

        // 새 일정(busy window) 없이 소요시간만 줄였으므로 겹치는 게 없다 → 남은 태스크는 원래 시각에서 길이만 바뀐다
        response.planChanged shouldBe true
        response.updatedTasks!!.map { Triple(it.taskName, it.startTime, it.status) } shouldBe listOf(
            Triple("완료한 태스크", "19:00", PlanTaskStatus.DONE),
            Triple("남은 태스크1", "20:10", PlanTaskStatus.PENDING),
            Triple("남은 태스크2", "21:10", PlanTaskStatus.PENDING)
        )
        response.updatedTasks!!.map { it.endTime } shouldBe listOf("20:00", "20:50", "21:50")
    }
    "채팅으로 계획을 재조정해도 실패한 태스크는 AI 에 보내지 않고, 지우거나 옮기지 않는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val failedId = seedTask(dailyPlanId, "실패한 태스크", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false, failed = true)
        seedTask(dailyPlanId, "남은 태스크", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "줄였어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("남은 태스크", 40)))
            )
        )
        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "10분 줄여줘")

        llm.lastCurrentTasksJson!! shouldNotContain "실패한 태스크"
        transaction(db) {
            val failed = PlanTaskRow.findById(failedId)!!
            failed.status shouldBe PlanTaskStatus.FAILED
            failed.startTime shouldBe LocalTime.of(17, 0)
        }
        response.updatedTasks!!.map { it.taskName to it.status } shouldBe listOf(
            "실패한 태스크" to PlanTaskStatus.FAILED,
            "남은 태스크" to PlanTaskStatus.PENDING
        )
    }

    "채팅으로 계획을 다시 짜도 AI 가 만든 할일은 지우지 않고, 그 시간대를 피해 새 할일을 배치한다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        // 귀가(16:30~17:10)·저녁(18:00~19:00) 뒤에 둔다
        val aiId = seedTask(dailyPlanId, "AI 복습", LocalTime.of(20, 10), LocalTime.of(21, 0), completed = false, origin = PlanTaskOrigin.AI)
        val mineId = seedTask(dailyPlanId, "내가 추가한 할일", LocalTime.of(21, 10), LocalTime.of(22, 0), completed = false)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "계획을 다시 짰어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("새 할일", 30)))
            )
        )
        ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "계획 다시 짜줘")

        transaction(db) {
            // AI 가 만든 할일은 지울 수 없어 시각 그대로 남는다
            val ai = PlanTaskRow.findById(aiId)!!
            ai.startTime shouldBe LocalTime.of(20, 10)
            ai.origin shouldBe PlanTaskOrigin.AI
            // 사용자가 만든 할일은 AI 목록에서 빠졌으므로 지워진다
            PlanTaskRow.findById(mineId) shouldBe null
            // 새 할일은 AI 할일 시간대와 겹치지 않는다
            val created = PlanTaskRow.all().single { it.taskName == "새 할일" }
            (created.startTime < ai.endTime && ai.startTime < created.endTime) shouldBe false
        }
    }

    "AI 에 보내는 현재 태스크에 시작·끝 시각이 들어간다 (새 일정과 겹치는지 판단용)" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학", LocalTime.of(16, 10), LocalTime.of(16, 50), completed = false)
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "그 시간엔 공부가 없어서 그대로 둘게요", plan_changed = false))

        ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "저녁 7시부터 9시까지 학원이야")

        llm.lastCurrentTasksJson shouldBe """[{"task_name":"수학","estimated_minutes":40,"start_time":"16:10","end_time":"16:50"}]"""
    }

    "AI 가 계획을 바꿨다고 했지만 적용할 변경이 없으면, 바뀐 줄 알지 않도록 답변을 바로잡는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학", LocalTime.of(16, 10), LocalTime.of(16, 50), completed = false)
        // plan_changed=true 인데 plan_update 가 없음
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "조정해 드릴게요!", plan_changed = true, plan_update = null))

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "저녁에 학원 가")

        response.planChanged shouldBe false
        response.reply shouldBe PLAN_NOT_APPLIED_REPLY
        // 대화 기록에도 바로잡은 답변이 남는다
        ChatService(llm, schoolDataFetcher, otherDayClock).getHistory(userId, dailyPlanId).last().content shouldBe PLAN_NOT_APPLIED_REPLY
    }

    "새 일정 시간이 남은 태스크와 안 겹치면, AI 가 바꾸자고 해도 계획을 그대로 두고 그렇게 답한다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학", LocalTime.of(16, 10), LocalTime.of(16, 50), completed = false)
        seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 0), LocalTime.of(18, 30), completed = false)
        // 실제 LLM 이 한 것처럼: 18:00~18:30 이 19~21시와 겹친다고 착각해 태스크를 빼려 함
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "문제 풀이 시간과 겹쳐서 조정해 드릴게요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("수학", 40)), busy_window_start = "19:00", busy_window_end = "21:00")
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "저녁 7시부터 9시까지 학원이야")

        response.planChanged shouldBe false
        response.reply shouldBe "그 시간(19:00~21:00)에는 잡혀 있는 공부가 없어서 오늘 계획은 그대로 둘게요."
        transaction(db) {
            PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }.map { it.taskName }.sorted()
        } shouldBe listOf("문제 풀이", "수학")
    }

    "새 일정 시간이 남은 태스크와 겹치면 계획을 다시 배치한다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학", LocalTime.of(16, 30), LocalTime.of(17, 10), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "학원 시간을 피해서 옮겼어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("수학", 40)), busy_window_start = "16:30", busy_window_end = "18:00")
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "4시 반부터 6시까지 학원이야")

        response.planChanged shouldBe true
        response.reply shouldBe "학원 시간을 피해서 옮겼어요"
        response.updatedTasks!!.single().startTime shouldBe "19:00" // 학원 끝(18:00) 뒤, 저녁(18:00~19:00) 다음
    }

    "재조정해도 새 일정과 안 겹치는 태스크는 id·시각이 그대로고, 겹치는 태스크만 원래 시각 이후로 옮긴다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val a = seedTask(dailyPlanId, "개념 정리", LocalTime.of(19, 0), LocalTime.of(19, 40), completed = false)
        val b = seedTask(dailyPlanId, "문제 풀이", LocalTime.of(20, 30), LocalTime.of(21, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "학원 시간을 피해서 옮겼어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(
                    tasks = listOf(AiChatTask("개념 정리", 40), AiChatTask("문제 풀이", 30)),
                    busy_window_start = "20:00", busy_window_end = "21:30"
                )
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "8시부터 9시 반까지 학원")

        response.updatedTasks!!.map { Triple(it.id, it.taskName, "${it.startTime}~${it.endTime}") } shouldBe listOf(
            Triple(a, "개념 정리", "19:00~19:40"),   // 안 겹침 → 그대로 (id 유지)
            Triple(b, "문제 풀이", "21:30~22:00")    // 겹침 → 학원 끝난 뒤로 (id 유지)
        )
    }

    "오늘 계획을 재조정하면 옮기는 태스크를 이미 지난 시간에 넣지 않는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "개념 정리", LocalTime.of(16, 40), LocalTime.of(17, 20), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "옮겼어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("개념 정리", 40)), busy_window_start = "16:30", busy_window_end = "17:30")
            )
        )
        // 계획 날짜(2026-09-29) 한국 시간 19:05 — 학원 끝(17:30) 뒤 빈 시간 중 지금 이후인 19:10 부터 (17:30~18:00 은 이미 지남)
        val todayClock = Clock.fixed(Instant.parse("2026-09-29T10:05:00Z"), ZoneOffset.UTC)

        val response = ChatService(llm, schoolDataFetcher, todayClock).sendMessage(userId, dailyPlanId, "4시 반부터 5시 반까지 학원이었어")

        response.updatedTasks!!.single().startTime shouldBe "19:10"
    }

    "시험 날짜를 물으면 답할 수 있도록 플랜보드의 시험일과 D-day 를 AI 에 넘긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(examDate = LocalDate.of(2026, 10, 5))
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "10월 5일이에요", plan_changed = false))

        ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "시험 언제야?")

        // 대화 날짜(2026-09-29) 기준 D-6
        llm.lastPlanBoardSummary shouldBe "플랜보드: 테스트 보드\n학습 기간: 2026-09-29 ~ 2026-09-29\n시험일: 2026-10-05 (D-6)"
    }

    "시험일이 없는 플랜보드면 '등록되지 않음' 으로 넘긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(examDate = null)
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "아직 몰라요", plan_changed = false))

        ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "시험 언제야?")

        llm.lastPlanBoardSummary!!.lines().last() shouldBe "시험일: 등록되지 않음"
    }

    "공부 스타일 설문은 숫자가 아니라 학생이 고른 보기 문구로 AI 에 넘긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(examDate = null)
        transaction(db) {
            StudyStyleAnswerRow.new { user = UserRow[userId]; questionNumber = 2; answerOption = 1 }
            StudyStyleAnswerRow.new { user = UserRow[userId]; questionNumber = 7; answerOption = 4 } // 모르겠어요 → 빠짐
        }
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "좋아요", plan_changed = false))

        ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "안녕")

        llm.lastStudyStyleSummary shouldBe "공부가 잘 되는 시간대: 아침 (등교 전)"
    }

    fun postponeLlm(names: List<String>, toDate: String? = null, reply: String = "미룰게요") = FakeChatLlmClient(
        AiChatResult(reply_message = reply, plan_changed = true, postpone = AiChatPostpone(task_names = names, to_date = toDate))
    )

    "미루기: 오늘 할일을 다음 날로 옮기고, 그날 기존 할일을 쉬는 시간 두고 피해서 배치하고, 원래 날짜를 남긴다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val conceptId = seedTask(dailyPlanId, "개념 정리", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false)
        val problemId = seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)
        // 수요일(등교일, 학교 정보 없음 → 16:30 하교·17:10 귀가, 저녁 18:00~19:00)에 이미 있는 할일 19:00~19:30
        val wednesdayPlanId = transaction(db) {
            DailyPlanRow.new { planBoard = DailyPlanRow[dailyPlanId].planBoard; planDate = wednesday }.id.value
        }
        seedTask(wednesdayPlanId, "영어 단어", LocalTime.of(19, 0), LocalTime.of(19, 30), completed = false)
        val llm = postponeLlm(listOf("개념 정리", "문제 풀이"))

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "오늘 거 내일로 미뤄줘")

        response.planChanged shouldBe true
        response.updatedTasks shouldBe emptyList()
        // 17:10~18:00(50분)엔 60분 할일이 안 들어가고, 기존 할일 앞뒤 10분을 비운 19:40 부터
        response.movedTasks!!.map { "${it.taskName} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "개념 정리 19:40~20:40", "문제 풀이 20:50~21:40"
        )
        response.movedTasks!!.map { it.dailyPlanId }.toSet() shouldBe setOf(wednesdayPlanId)
        response.reply shouldBe "'개념 정리', '문제 풀이' 할일을 9월 30일(수) 19:40~20:40, 20:50~21:40로 미뤘어요."
        transaction(db) {
            listOf(conceptId, problemId).map { PlanTaskRow[it].postponedFromDate } shouldBe listOf(tuesday, tuesday)
        }
        llm.lastToday shouldBe "2026-09-01 (화요일)\n이번 주: 2026-08-31(월) ~ 2026-09-06(일)\n다음 주: 2026-09-07(월) ~ 2026-09-13(일)"
    }

    // ---- 할일 직접 편집 (#306) ----

    fun editLlm(vararg edits: AiChatEdit) = FakeChatLlmClient(AiChatResult(reply_message = "바꿀게요", plan_changed = true, edits = edits.toList()))

    "할일 직접 편집: 줄이기·빼기·추가를 서버가 적용하고, 답변은 실제 결과로 만들고, 추가한 할일엔 플랜보드 과목을 붙인다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학 문제 풀기", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false)
        seedTask(dailyPlanId, "영어 단어", LocalTime.of(18, 10), LocalTime.of(18, 40), completed = false)
        val scienceId = transaction(db) {
            val science = SubjectRow.find { SubjectTable.name eq "과학" }.firstOrNull() ?: SubjectRow.new { name = "과학" }
            val textbook = TextbookRow.new { subject = science; title = "과학 교과서" }
            val first = ChapterRow.new { this.textbook = textbook; chapterName = "물질의 구성"; chapterOrder = 1 }
            val second = ChapterRow.new { this.textbook = textbook; chapterName = "전기와 자기"; chapterOrder = 2 }
            PlanSubjectRow.new { planBoard = DailyPlanRow[dailyPlanId].planBoard; this.textbook = textbook; startChapter = first; endChapter = second }
            science.id.value
        }
        val llm = editLlm(
            AiChatEdit("resize", "수학 문제 풀기", minutes = 30),
            AiChatEdit("delete", "영어 단어"),
            AiChatEdit("add", "과학 2단원 전기와 자기 개념 정리", minutes = 40, start_time = "20:00", subject_name = "과학")
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "수학 30분으로 줄이고 영어 빼고 8시에 과학 넣어줘")

        response.planChanged shouldBe true
        response.updatedTasks!!.map { "${it.taskName} ${it.startTime}~${it.endTime} ${it.estimatedMinutes} ${it.subjectName}" } shouldBe listOf(
            "수학 문제 풀기 17:00~17:30 30 null",
            "과학 2단원 전기와 자기 개념 정리 20:00~20:40 40 과학"
        )
        response.updatedTasks!!.last().subjectId shouldBe scienceId
        // 챗봇이 만든 할일 = user 발 일정이라 나중에 지울 수 있다 (#245)
        transaction(db) { PlanTaskRow.all().single { it.taskName.startsWith("과학 2단원") }.origin } shouldBe PlanTaskOrigin.USER
        response.reply shouldBe "'수학 문제 풀기'을(를) 30분(17:00~17:30)으로 줄였어요. '영어 단어'을(를) 오늘 계획에서 뺐어요. " +
            "'과학 2단원 전기와 자기 개념 정리'을(를) 20:00~20:40에 추가했어요."
        // AI 가 학습 범위를 보고 할일 이름을 짓도록 플랜보드 과목·단원을 넘긴다
        llm.lastPlanSubjects shouldBe "- 과학 (과학 교과서): 물질의 구성, 전기와 자기"
        transaction(db) { ChatTurnRow.find { ChatTurnTable.dailyPlanId eq dailyPlanId }.map { it.role } } shouldBe listOf("user", "assistant")
    }

    "할일 직접 편집: 모두 거절되면 계획은 그대로 두고 이유와 빈 시간을 답한다, 퀴즈를 시작한 할일은 못 뺀다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val mathId = seedTask(dailyPlanId, "수학 문제 풀기", LocalTime.of(19, 0), LocalTime.of(20, 0), completed = false)
        seedTask(dailyPlanId, "영어 단어", LocalTime.of(20, 10), LocalTime.of(20, 40), completed = false)
        transaction(db) {
            TaskQuizAttemptTable.insert {
                it[planTaskId] = mathId; it[attemptNumber] = 1; it[totalCount] = 5; it[createdAt] = OffsetDateTime.now()
            }
        }
        val llm = editLlm(AiChatEdit("delete", "수학 문제 풀기"), AiChatEdit("move", "영어 단어", start_time = "19:30"))

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "수학 빼고 영어 7시 반으로")

        response.planChanged shouldBe false
        response.updatedTasks shouldBe null
        // 빈 시간은 귀가(~17:10)·저녁(18:00~19:00)·두 할일을 뺀 시간
        response.reply shouldBe "'수학 문제 풀기'은(는) 이미 퀴즈를 시작한 할일이라 뺄 수 없어요. " +
            "'영어 단어'을(를) 19:30로 옮기지 못했어요 — 그 시간에 '수학 문제 풀기' 할일이 있어요. 오늘 빈 시간: 17:10~18:00, 20:00~20:10, 20:40~23:00"
        transaction(db) { PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }.count() } shouldBe 2L
    }

    "미루기 결과 할일에도 과목과 보드 종류(isManual)가 실린다" {
        val (userId, dailyPlanId) = seedDailyPlan(examDate = tuesday.plusDays(8), boardEnd = tuesday.plusDays(7)) // 시험 보드
        val taskId = seedTask(dailyPlanId, "개념 정리", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false)
        val subjectId = transaction(db) {
            val subject = SubjectRow.find { SubjectTable.name eq "수학" }.firstOrNull() ?: SubjectRow.new { name = "수학" }
            PlanTaskRow[taskId].subjectId = subject.id
            subject.id.value
        }

        val response = ChatService(postponeLlm(listOf("개념 정리")), schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "내일로 미뤄줘")

        response.movedTasks!!.single().let {
            it.subjectId shouldBe subjectId
            it.subjectName shouldBe "수학"
            it.isManual shouldBe false
        }
    }

    "미루기: 어제에서 미뤄져 온 할일은 이틀 연속 미루지 못하고, 다른 할일만 옮긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val postponedId = seedTask(dailyPlanId, "개념 정리", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false, postponedFrom = tuesday.minusDays(1))
        seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)
        val llm = postponeLlm(listOf("개념 정리", "문제 풀이"))

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "다 내일로 미뤄줘")

        response.movedTasks!!.map { it.taskName } shouldBe listOf("문제 풀이")
        response.updatedTasks!!.map { it.taskName } shouldBe listOf("개념 정리")
        response.reply shouldBe "'문제 풀이' 할일을 9월 30일(수) 17:10~18:00로 미뤘어요. '개념 정리'은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요."
        transaction(db) { PlanTaskRow[postponedId].dailyPlan.id.value } shouldBe dailyPlanId
        // AI 에도 미룰 수 없는 할일이라고 알려준다
        llm.lastCurrentTasksJson!!.contains("\"can_postpone\":false") shouldBe true
    }

    "미루기: AI 가 미룰 수 없는 할일을 알아서 빼고 답변에서만 언급하면, 그 이유도 답변에 붙인다" {
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        seedTask(dailyPlanId, "영어 단어", LocalTime.of(17, 0), LocalTime.of(17, 40), completed = false, postponedFrom = tuesday.minusDays(1))
        seedTask(dailyPlanId, "수학 개념", LocalTime.of(18, 0), LocalTime.of(19, 0), completed = false)
        val llm = postponeLlm(listOf("수학 개념"), reply = "'수학 개념'은 내일로 미룰게요. '영어 단어'는 어제 미룬 거라 오늘 해야 해요.")

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "오늘 거 다 내일로")

        // 귀가 뒤 17:10~18:00(50분)엔 60분이 안 들어가 저녁 뒤로
        response.reply shouldBe "'수학 개념' 할일을 9월 30일(수) 19:00~20:00로 미뤘어요. '영어 단어'은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요."
    }

    "미루기: 미룰 수 있는 할일이 하나도 없거나, 기간 밖·과거 날짜거나, 이름이 안 맞으면 옮기지 않고 이유를 답한다" {
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(3))
        seedTask(dailyPlanId, "어제 미룬 것", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false, postponedFrom = tuesday.minusDays(1))
        seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)
        suspend fun send(llm: FakeChatLlmClient) = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "미뤄줘")

        send(postponeLlm(listOf("어제 미룬 것"))).let {
            it.planChanged shouldBe false
            it.reply shouldBe postponeBlockedReply(listOf("어제 미룬 것"))
        }
        send(postponeLlm(listOf("문제 풀이"), toDate = "2026-10-10")).reply shouldBe
            "플랜보드 기간이 10월 2일(금)까지라서 10월 10일(토)로는 미룰 수 없어요."
        send(postponeLlm(listOf("문제 풀이"), toDate = "2026-09-29")).reply shouldBe "할일은 9월 30일(수) 이후 날짜로만 미룰 수 있어요."
        send(postponeLlm(listOf("없는 할일"))).reply shouldBe POSTPONE_NOTHING_REPLY
        transaction(db) { PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }.count() } shouldBe 2L
    }

    fun seedOtherDay(dailyPlanId: Int, date: LocalDate): Int = transaction(db) {
        DailyPlanRow.new {
            planBoard = DailyPlanRow[dailyPlanId].planBoard
            planDate = date
        }.id.value
    }

    "다른 날 일정을 말하면 그날 계획에서 겹치는 할일만 옮기고, 대화한 날 계획은 그대로 둔다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val todayTaskId = seedTask(dailyPlanId, "오늘 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        val keptId = seedTask(wedPlanId, "개념 정리", LocalTime.of(17, 10), LocalTime.of(17, 50), completed = false)
        val movedId = seedTask(wedPlanId, "암기", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val aiId = seedTask(wedPlanId, "AI 복습", LocalTime.of(20, 10), LocalTime.of(20, 40), completed = false, origin = PlanTaskOrigin.AI)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "수요일 학원 일정 알려줘서 고마워요",
                plan_changed = true,
                other_busy_days = listOf(AiChatBusyDay(wednesday.toString(), "19:00", "21:00"))
            )
        )
        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "수요일 저녁 7시부터 9시까지 학원이야")

        response.planChanged shouldBe true
        response.updatedTasks shouldBe null
        response.updatedDays!!.single().let { day ->
            day.date shouldBe wednesday.toString()
            day.dailyPlanId shouldBe wedPlanId
            day.tasks.map { it.taskName to it.startTime } shouldBe listOf(
                "개념 정리" to "17:10",
                "AI 복습" to "20:10",
                "암기" to "21:00"
            )
        }
        // 다른 날만 바뀌면 AI 문장 대신 실제로 옮긴 결과로 답한다
        response.reply shouldBe "9월 30일(수) 19:00~21:00 일정을 피해서 '암기' 할일을 21:00~21:50로 옮겼어요."
        transaction(db) {
            PlanTaskRow[todayTaskId].startTime shouldBe LocalTime.of(19, 10)
            PlanTaskRow[keptId].startTime shouldBe LocalTime.of(17, 10)
            PlanTaskRow[aiId].startTime shouldBe LocalTime.of(20, 10)
            PlanTaskRow[movedId].let {
                it.startTime shouldBe LocalTime.of(21, 0)
                it.endTime shouldBe LocalTime.of(21, 50)
                it.dailyPlan.id.value shouldBe wedPlanId
            }
        }
    }

    "대화한 날과 다른 날 일정을 함께 말하면 두 날 모두 반영한다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        seedTask(dailyPlanId, "오늘 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        seedTask(wedPlanId, "암기", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "학원 시간을 피해서 옮겼어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("오늘 수학", 50)), busy_window_start = "19:00", busy_window_end = "21:00"),
                other_busy_days = listOf(AiChatBusyDay(wednesday.toString(), "19:00", "21:00"))
            )
        )
        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "오늘이랑 내일 7시부터 9시까지 학원이야")

        response.updatedTasks!!.single().startTime shouldBe "21:00"
        response.updatedDays!!.single().tasks.single().startTime shouldBe "21:00"
    }

    "다른 날 일정인데 그날 계획이 없거나 겹치는 할일이 없거나 기간 밖이면, 아무것도 바꾸지 않고 그렇게 답한다" {
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(3))
        val todayTaskId = seedTask(dailyPlanId, "오늘 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, tuesday.plusDays(1))
        seedTask(wedPlanId, "암기", LocalTime.of(16, 10), LocalTime.of(16, 50), completed = false)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "알겠어요",
                plan_changed = true,
                // AI 가 대화한 날 할일을 그대로 다시 보내도 대화한 날은 다시 배치하지 않는다
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("오늘 수학", 50))),
                other_busy_days = listOf(
                    AiChatBusyDay(tuesday.plusDays(1).toString(), "19:00", "21:00"), // 겹치는 할일 없음
                    AiChatBusyDay(tuesday.plusDays(2).toString(), "19:00", "21:00"), // 계획 없음
                    AiChatBusyDay(tuesday.plusDays(5).toString(), "19:00", "21:00") // 플랜보드 기간 밖
                )
            )
        )
        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "이번 주 저녁마다 학원이야")

        response.planChanged shouldBe false
        response.updatedTasks shouldBe null
        response.updatedDays shouldBe null
        response.reply shouldBe "9월 30일(수), 10월 1일(목)에는 그 시간에 잡힌 할일이 없어서 그대로예요. " +
            "10월 4일(일)은(는) 지난 날이거나 플랜보드 기간(9월 29일(화)~10월 2일(금)) 밖이라 바꾸지 않았어요."
        transaction(db) { PlanTaskRow[todayTaskId].startTime shouldBe LocalTime.of(19, 10) }
    }

    "대화한 날 할일을 줄여 달라면서 다른 날 일정을 말하면, 대화한 날 변경도 빠뜨리지 않는다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val todayId = seedTask(dailyPlanId, "오늘 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        seedTask(wedPlanId, "암기", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "오늘 수학은 30분으로 줄였어요",
                plan_changed = true,
                // busy_window 없이 대화한 날 할일 분만 바뀐 경우
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("오늘 수학", 30))),
                other_busy_days = listOf(AiChatBusyDay(wednesday.toString(), "19:00", "21:00"))
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "오늘 수학 30분으로 줄여줘, 수요일엔 7~9시 학원 있어")

        response.updatedTasks!!.single().estimatedMinutes shouldBe 30
        response.updatedDays!!.single().tasks.single().startTime shouldBe "21:00"
        transaction(db) { PlanTaskRow[todayId].estimatedMinutes shouldBe 30 }
    }

    "AI 가 plan_changed=false 로 답해도 다른 날 일정을 줬으면 반영한다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        seedTask(dailyPlanId, "오늘 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        seedTask(wedPlanId, "암기", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "알려줘서 고마워요",
                plan_changed = false,
                other_busy_days = listOf(AiChatBusyDay(wednesday.toString(), "19:00", "21:00"))
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "수요일 7~9시 학원 있어")

        response.planChanged shouldBe true
        response.updatedDays!!.single().tasks.single().startTime shouldBe "21:00"
        response.reply shouldBe "9월 30일(수) 19:00~21:00 일정을 피해서 '암기' 할일을 21:00~21:50로 옮겼어요."
    }

    "할일 편집과 다른 날 일정을 함께 말하면 둘 다 반영하고, 같은 날이면 편집한 계획 기준으로 옮긴다" {
        val wednesday = tuesday.plusDays(1)
        val thursday = tuesday.plusDays(2)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        seedTask(dailyPlanId, "오늘 영어", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        val wedId = seedTask(wedPlanId, "암기", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val thuPlanId = seedOtherDay(dailyPlanId, thursday)
        seedTask(thuPlanId, "목 과학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "바꿀게요",
                plan_changed = true,
                edits = listOf(
                    AiChatEdit(type = "delete", task_name = "오늘 영어"),
                    AiChatEdit(type = "resize", task_name = "암기", minutes = 30, date = wednesday.toString())
                ),
                other_busy_days = listOf(
                    AiChatBusyDay(wednesday.toString(), "19:00", "21:00"),
                    AiChatBusyDay(thursday.toString(), "19:00", "21:00")
                )
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "오늘 영어 빼고 수요일 암기 30분으로, 수·목 7~9시 학원")

        response.planChanged shouldBe true
        response.updatedTasks shouldBe emptyList()
        response.updatedDays!!.map { day -> day.date to day.tasks.map { Triple(it.taskName, it.startTime, it.endTime) } } shouldBe listOf(
            wednesday.toString() to listOf(Triple("암기", "21:00", "21:30")),
            thursday.toString() to listOf(Triple("목 과학", "21:00", "21:50"))
        )
        response.reply shouldContain "'오늘 영어'을(를) 오늘 계획에서 뺐어요."
        response.reply shouldContain "'목 과학'"
        transaction(db) { PlanTaskRow[wedId].estimatedMinutes shouldBe 30 }
    }

    "미루기와 다른 날 일정을 함께 말하면 둘 다 반영한다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        seedTask(dailyPlanId, "오늘 영어", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        seedTask(wedPlanId, "암기", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "미룰게요",
                plan_changed = true,
                postpone = AiChatPostpone(task_names = listOf("오늘 영어"), to_date = tuesday.plusDays(2).toString()),
                other_busy_days = listOf(AiChatBusyDay(wednesday.toString(), "19:00", "21:00"))
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "영어 목요일로 미뤄줘, 수요일엔 7~9시 학원")

        response.updatedTasks shouldBe emptyList()
        response.movedTasks!!.single().taskName shouldBe "오늘 영어"
        response.updatedDays!!.single().tasks.single().startTime shouldBe "21:00"
        ChatService(llm, schoolDataFetcher, otherDayClock).getHistory(userId, dailyPlanId).last().content shouldBe response.reply
    }

    "전체 계획(#337): AI 에 플랜보드 기간의 날짜별 할일을 보여주고, 지난 날은 수정 불가로 표시한다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(2))
        seedTask(dailyPlanId, "화 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = true)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        seedTask(wedPlanId, "수 과학", LocalTime.of(20, 0), LocalTime.of(20, 50), completed = false)
        // 오늘 = 수요일 (KST 9/30 09:00)
        val wednesdayClock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC)
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "네"))

        ChatService(llm, schoolDataFetcher, wednesdayClock).sendMessage(userId, wedPlanId, "이번 주 계획 어때?")

        llm.lastPlanDays shouldBe listOf(
            "- 2026-09-29 (화) [지난 날·수정 불가]: 19:10~20:00 화 수학 (50분, 완료)",
            "- 2026-09-30 (수) [대화 중인 날, 오늘, 수정 가능]: 20:00~20:50 수 과학 (50분, 대기)",
            "- 2026-10-01 (목) [수정 가능]: 할일 없음"
        ).joinToString("\n")
    }

    "전체 계획(#337): 다른 날 할일을 고치면 그날만 바꾸고 updatedDays 로 돌려준다" {
        val thursday = tuesday.plusDays(2)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val todayId = seedTask(dailyPlanId, "오늘 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val thuPlanId = seedOtherDay(dailyPlanId, thursday)
        val thuMathId = seedTask(thuPlanId, "목 수학 문제", LocalTime.of(19, 10), LocalTime.of(20, 10), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "줄일게요",
                plan_changed = true,
                edits = listOf(AiChatEdit(type = "resize", task_name = "목 수학 문제", minutes = 30, date = thursday.toString()))
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "목요일 수학 30분으로 줄여줘")

        response.planChanged shouldBe true
        response.updatedTasks shouldBe null
        response.updatedDays!!.single().let { day ->
            day.date shouldBe thursday.toString()
            day.dailyPlanId shouldBe thuPlanId
            day.tasks.map { Triple(it.taskName, it.startTime, it.endTime) } shouldBe listOf(Triple("목 수학 문제", "19:10", "19:40"))
        }
        response.reply shouldBe "10월 1일(목) '목 수학 문제'을(를) 30분(19:10~19:40)으로 줄였어요."
        transaction(db) {
            PlanTaskRow[thuMathId].estimatedMinutes shouldBe 30
            PlanTaskRow[todayId].endTime shouldBe LocalTime.of(20, 0)
        }
    }

    "전체 계획(#337): 대화한 날과 여러 날을 한 번에 고치고, 계획이 없던 날은 만들어서 추가한다" {
        val wednesday = tuesday.plusDays(1)
        val saturday = tuesday.plusDays(4)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        seedTask(dailyPlanId, "오늘 영어", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        seedTask(wedPlanId, "수 영어", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "바꿀게요",
                plan_changed = true,
                edits = listOf(
                    AiChatEdit(type = "delete", task_name = "오늘 영어"),
                    AiChatEdit(type = "delete", task_name = "수 영어", date = wednesday.toString()),
                    AiChatEdit(type = "add", task_name = "과학 복습", minutes = 60, start_time = "10:00", date = saturday.toString())
                )
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "이번 주 영어 빼고 토요일 10시에 과학 1시간 넣어줘")

        response.updatedTasks shouldBe emptyList()
        response.updatedDays!!.map { it.date to it.tasks.map { t -> t.taskName } } shouldBe listOf(
            wednesday.toString() to emptyList(),
            saturday.toString() to listOf("과학 복습")
        )
        // 다른 날 문장에는 날짜를 붙인다 (이미 날짜가 들어간 문장은 그대로)
        response.reply shouldBe "'오늘 영어'을(를) 오늘 계획에서 뺐어요. " +
            "'수 영어'을(를) 9월 30일(수) 계획에서 뺐어요. " +
            "10월 3일(토) '과학 복습'을(를) 10:00~11:00에 추가했어요."
        transaction(db) {
            val satPlan = DailyPlanRow.find { (DailyPlanTable.planDate eq saturday) }.single()
            PlanTaskRow.find { PlanTaskTable.dailyPlanId eq satPlan.id }.single().origin shouldBe PlanTaskOrigin.USER
        }
    }

    "전체 계획(#337): 지난 날·플랜보드 기간 밖은 고치지 않고 그렇게 답한다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(2))
        val tueId = seedTask(dailyPlanId, "화 수학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val wedPlanId = seedOtherDay(dailyPlanId, wednesday)
        val wednesdayClock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "바꿀게요",
                plan_changed = true,
                edits = listOf(
                    AiChatEdit(type = "delete", task_name = "화 수학", date = tuesday.toString()),
                    AiChatEdit(type = "add", task_name = "과학", minutes = 30, date = tuesday.plusDays(10).toString())
                )
            )
        )

        val response = ChatService(llm, schoolDataFetcher, wednesdayClock).sendMessage(userId, wedPlanId, "어제 수학 빼고 다음 주에 과학 넣어줘")

        response.planChanged shouldBe false
        response.reply shouldBe "9월 29일(화)은(는) 지난 날이거나 플랜보드 기간 밖이라 고칠 수 없어요. " +
            "10월 9일(금)은(는) 지난 날이거나 플랜보드 기간 밖이라 고칠 수 없어요."
        transaction(db) { PlanTaskRow.findById(tueId) shouldNotBe null }
    }

    "전체 계획(#337): 다른 날 할일도 from_date 로 미룰 수 있다" {
        val thursday = tuesday.plusDays(2)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val thuPlanId = seedOtherDay(dailyPlanId, thursday)
        val thuId = seedTask(thuPlanId, "목 과학", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "미룰게요",
                plan_changed = true,
                postpone = AiChatPostpone(task_names = listOf("목 과학"), to_date = thursday.plusDays(1).toString(), from_date = thursday.toString())
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "목요일 과학 금요일로 미뤄줘")

        response.planChanged shouldBe true
        response.updatedTasks shouldBe null
        response.updatedDays!!.single().let { it.dailyPlanId shouldBe thuPlanId; it.tasks shouldBe emptyList() }
        response.movedTasks!!.single().taskName shouldBe "목 과학"
        transaction(db) {
            PlanTaskRow[thuId].let {
                it.dailyPlan.planDate shouldBe thursday.plusDays(1)
                it.postponedFromDate shouldBe thursday
            }
        }
    }

    "planBoardSummaryOf: 시험 당일과 지난 시험일을 구분한다" {
        val day = LocalDate.of(2026, 10, 5)
        planBoardSummaryOf("보드", day, day, day, day).lines().last() shouldBe "시험일: 2026-10-05 (D-day(오늘))"
        planBoardSummaryOf("보드", day, day, day, day.plusDays(2)).lines().last() shouldBe "시험일: 2026-10-05 (이미 지남(2일 전))"
    }
})


private class FakeChatLlmClient(private val result: AiChatResult?) : ChatLlmClient(dummyAppConfig()) {
    var lastCurrentTasksJson: String? = null
    var lastPlanBoardSummary: String? = null
    var lastToday: String? = null
    var lastStudyStyleSummary: String? = null
    var lastPlanSubjects: String? = null
    var lastPlanDays: String? = null

    override suspend fun adjustPlan(
        grade: Int,
        studyStyleSummary: String,
        targetDate: String,
        today: String,
        currentTasksJson: String,
        chatHistoryJson: String,
        planBoardSummary: String,
        userMessage: String,
        planSubjects: String,
        planDays: String
    ): AiChatResult? {
        lastPlanSubjects = planSubjects
        lastPlanDays = planDays
        lastToday = today
        lastStudyStyleSummary = studyStyleSummary
        lastCurrentTasksJson = currentTasksJson
        lastPlanBoardSummary = planBoardSummary
        return result
    }
}

private fun dummyAppConfig() = AppConfig(
    environment = EnvironmentMode.DEV,
    jdbcUrl = "", dbUsername = "", dbPassword = "", dbMaxPoolSize = 1,
    corsAllowedHosts = emptyList(), corsMaxAgeSeconds = 0,
    storageType = "local", storageBaseDir = null, storageBaseUrl = null, storageBaseRoute = null,
    storageAwsRegion = null, storageAwsBucket = null,
    jwtSecret = "", jwtIssuer = "",
    llmBaseUrl = "", llmApiKey = "", llmModel = "",
    niceApiKey = "", niceBaseUrl = "",
    googleClientId = "", appleClientId = ""
)

private fun ensureTestDatabase(url: String, user: String, password: String) {
    val dbName = url.substringAfterLast("/")
    val adminUrl = url.substringBeforeLast("/") + "/postgres"
    try {
        DriverManager.getConnection(adminUrl, user, password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $dbName") }
        }
    } catch (e: SQLException) {
        // 42P04: duplicate_database — 이미 존재하면 통과
        if (e.sqlState != "42P04") throw e
    }
}
