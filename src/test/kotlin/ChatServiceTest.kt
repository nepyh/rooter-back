import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.chat.ChatLlmClient
import com.github.nepyh.rooter.module.chat.ChatService
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

    fun seedDailyPlan(): Pair<Int, Int> = transaction(db) {
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
})

private class FakeChatLlmClient(private val result: AiChatResult?) : ChatLlmClient(dummyAppConfig()) {
    var lastCurrentTasksJson: String? = null

    override suspend fun adjustPlan(
        grade: Int,
        studyStyleSummary: String,
        targetDate: String,
        currentTasksJson: String,
        chatHistoryJson: String,
        userMessage: String
    ): AiChatResult? {
        lastCurrentTasksJson = currentTasksJson
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
