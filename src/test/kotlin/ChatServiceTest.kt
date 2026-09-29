import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.chat.ChatLlmClient
import com.github.nepyh.rooter.module.chat.ChatService
import com.github.nepyh.rooter.module.chat.PLAN_NOT_APPLIED_REPLY
import com.github.nepyh.rooter.module.chat.planBoardSummaryOf
import com.github.nepyh.rooter.module.chat.dto.AiChatPlanUpdate
import com.github.nepyh.rooter.module.chat.dto.AiChatResult
import com.github.nepyh.rooter.module.chat.dto.AiChatTask
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
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
            exec("DROP TABLE IF EXISTS plan_tasks CASCADE")
            exec("DROP TABLE IF EXISTS daily_plans CASCADE")
            exec("DROP TABLE IF EXISTS plan_boards CASCADE")
            exec("DROP TABLE IF EXISTS study_style_answers CASCADE")
            exec("DROP TABLE IF EXISTS user_unavailable_times CASCADE")
            exec("DROP TABLE IF EXISTS student_profiles CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(
                UserTable, StudentProfileTable, UnavailableTimeTable, StudyStyleAnswerTable,
                PlanBoardTable, DailyPlanTable, PlanTaskTable, ChatTurnTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            ChatTurnTable.deleteAll()
            PlanTaskTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    // 프로필(schoolId) 이 없으면 NICE 를 호출하지 않으므로, 호출되면 바로 실패하게 둔다
    val schoolDataFetcher = SchoolDataFetcher(
        NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") }))
    )

    val tuesday = LocalDate.of(2026, 9, 29)

    fun seedDailyPlan(examDate: LocalDate? = null): Pair<Int, Int> = transaction(db) {
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
            endDate = tuesday
            this.examDate = examDate
            createdAt = OffsetDateTime.now()
        }
        val dailyPlan = DailyPlanRow.new {
            planBoard = board
            planDate = tuesday
        }
        user.id.value to dailyPlan.id.value
    }

    fun seedTask(dailyPlanId: Int, name: String, start: LocalTime, end: LocalTime, completed: Boolean): Int = transaction(db) {
        PlanTaskRow.new {
            dailyPlan = DailyPlanRow[dailyPlanId]
            taskName = name
            startTime = start
            endTime = end
            estimatedMinutes = (end.toSecondOfDay() - start.toSecondOfDay()) / 60
            isCompleted = completed
        }.id.value
    }

    "채팅으로 계획을 재조정해도 이미 완료한 태스크는 그대로 남고, 새 태스크는 그 시간대를 피해 배치된다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val doneId = seedTask(dailyPlanId, "완료한 태스크", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = true)
        seedTask(dailyPlanId, "남은 태스크1", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)
        seedTask(dailyPlanId, "남은 태스크2", LocalTime.of(19, 10), LocalTime.of(20, 0), completed = false)

        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "줄였어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(tasks = listOf(AiChatTask("남은 태스크1", 40), AiChatTask("남은 태스크2", 40)))
            )
        )
        val response = ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "10분씩 줄여줘")

        // AI 에는 미완료 태스크만 전달된다
        llm.lastCurrentTasksJson!! shouldNotContain "완료한 태스크"

        // 완료 태스크는 id·시각·완료 상태가 그대로 유지된다
        transaction(db) {
            val done = PlanTaskRow.findById(doneId)!!
            done.isCompleted shouldBe true
            done.startTime shouldBe LocalTime.of(17, 0)
            done.endTime shouldBe LocalTime.of(18, 0)
            PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlanId }.count() shouldBe 3L
        }

        // 40분짜리는 하교(16:30)~완료 태스크(17:00) 사이 30분에 안 들어가므로, 완료 태스크가 끝난 18:00 부터 배치된다
        response.planChanged shouldBe true
        response.updatedTasks!!.map { Triple(it.taskName, it.startTime, it.isCompleted) } shouldBe listOf(
            Triple("완료한 태스크", "17:00", true),
            Triple("남은 태스크1", "18:00", false),
            Triple("남은 태스크2", "18:50", false)
        )
    }
    "AI 에 보내는 현재 태스크에 시작·끝 시각이 들어간다 (새 일정과 겹치는지 판단용)" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학", LocalTime.of(16, 10), LocalTime.of(16, 50), completed = false)
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "그 시간엔 공부가 없어서 그대로 둘게요", plan_changed = false))

        ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "저녁 7시부터 9시까지 학원이야")

        llm.lastCurrentTasksJson shouldBe """[{"task_name":"수학","estimated_minutes":40,"start_time":"16:10","end_time":"16:50"}]"""
    }

    "AI 가 계획을 바꿨다고 했지만 적용할 변경이 없으면, 바뀐 줄 알지 않도록 답변을 바로잡는다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        seedTask(dailyPlanId, "수학", LocalTime.of(16, 10), LocalTime.of(16, 50), completed = false)
        // plan_changed=true 인데 plan_update 가 없음
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "조정해 드릴게요!", plan_changed = true, plan_update = null))

        val response = ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "저녁에 학원 가")

        response.planChanged shouldBe false
        response.reply shouldBe PLAN_NOT_APPLIED_REPLY
        // 대화 기록에도 바로잡은 답변이 남는다
        ChatService(llm, schoolDataFetcher).getHistory(userId, dailyPlanId).last().content shouldBe PLAN_NOT_APPLIED_REPLY
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

        val response = ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "저녁 7시부터 9시까지 학원이야")

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

        val response = ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "4시 반부터 6시까지 학원이야")

        response.planChanged shouldBe true
        response.reply shouldBe "학원 시간을 피해서 옮겼어요"
        response.updatedTasks!!.single().startTime shouldBe "18:00"
    }

    "시험 날짜를 물으면 답할 수 있도록 플랜보드의 시험일과 D-day 를 AI 에 넘긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(examDate = LocalDate.of(2026, 10, 5))
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "10월 5일이에요", plan_changed = false))

        ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "시험 언제야?")

        // 대화 날짜(2026-09-29) 기준 D-6
        llm.lastPlanBoardSummary shouldBe "플랜보드: 테스트 보드\n학습 기간: 2026-09-29 ~ 2026-09-29\n시험일: 2026-10-05 (D-6)"
    }

    "시험일이 없는 플랜보드면 '등록되지 않음' 으로 넘긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(examDate = null)
        val llm = FakeChatLlmClient(AiChatResult(reply_message = "아직 몰라요", plan_changed = false))

        ChatService(llm, schoolDataFetcher).sendMessage(userId, dailyPlanId, "시험 언제야?")

        llm.lastPlanBoardSummary!!.lines().last() shouldBe "시험일: 등록되지 않음"
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

    override suspend fun adjustPlan(
        grade: Int,
        studyStyleSummary: String,
        targetDate: String,
        currentTasksJson: String,
        chatHistoryJson: String,
        planBoardSummary: String,
        userMessage: String
    ): AiChatResult? {
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
