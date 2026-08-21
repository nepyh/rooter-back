import com.github.nepyh.rooter.module.planboard.PlanBoardService
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * planboard 서비스 통합 테스트 (로컬 PostgreSQL 필요).
 *
 * 실행 전:
 *   - postgres 기동 (기본 localhost:5432, trust auth)
 *   - planboard_test DB 는 테스트가 자동 생성함
 *   - ./gradlew test
 *
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class PlanBoardServiceTest : StringSpec({

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
            SchemaUtils.drop(PlanTaskTable, DailyPlanTable, PlanBoardTable, UserTable, inBatch = true)
            SchemaUtils.create(UserTable, PlanBoardTable, DailyPlanTable, PlanTaskTable)
            // DDL(rooter-ddl) 의 uq_daily_plans_board_date 와 동일한 제약 — insertIgnore 레이스 방지 검증용
            exec("ALTER TABLE daily_plans ADD CONSTRAINT uq_daily_plans_board_date UNIQUE (plan_board_id, plan_date)")
        }
    }

    // 테스트 간 데이터 격리: 매 테스트 시작 전 전체 초기화 (FK 순서 주의)
    beforeEach {
        transaction(db) {
            PlanTaskTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val planBoardService = PlanBoardService()
    val planTaskService = PlanTaskService()

    fun seedUser(email: String): Int = transaction(db) {
        UserRow.new {
            this.email = email
            username = "tester"
            password = "x"
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    fun seedBoard(
        userId: Int,
        start: LocalDate = LocalDate.of(2026, 7, 1),
        end: LocalDate = LocalDate.of(2026, 7, 31)
    ): Int = transaction(db) {
        PlanBoardRow.new {
            user = UserRow[userId]
            title = "테스트 보드"
            this.startDate = start
            this.endDate = end
            createdAt = OffsetDateTime.now()
        }.id.value
    }

    // ---- createBoard ----

    "createBoard: 제목이 비어있으면 InvalidTitleException" {
        shouldThrow<PlanBoardValidationException.InvalidTitleException> {
            planBoardService.createBoard(1, PlanBoardCreateRequest("", "2026-07-01", "2026-07-31"))
        }
    }

    "createBoard: 제목이 100자 초과면 InvalidTitleException" {
        shouldThrow<PlanBoardValidationException.InvalidTitleException> {
            planBoardService.createBoard(1, PlanBoardCreateRequest("a".repeat(101), "2026-07-01", "2026-07-31"))
        }
    }

    "createBoard: 날짜 형식이 잘못되면 InvalidDateFormatException" {
        shouldThrow<PlanBoardValidationException.InvalidDateFormatException> {
            planBoardService.createBoard(1, PlanBoardCreateRequest("제목", "2026/07/01", "2026-07-31"))
        }
    }

    "createBoard: 종료일이 시작일보다 빠르면 InvalidDateRangeException" {
        shouldThrow<PlanBoardValidationException.InvalidDateRangeException> {
            planBoardService.createBoard(1, PlanBoardCreateRequest("제목", "2026-07-31", "2026-07-01"))
        }
    }

    "createBoard: 정상 요청이면 보드가 저장되고 id 를 반환한다" {
        val userId = seedUser("board-ok@test.com")
        val boardId = planBoardService.createBoard(userId, PlanBoardCreateRequest("여름방학", "2026-07-01", "2026-08-31"))

        val (title, ownerId) = transaction(db) {
            val saved = PlanBoardRow.findById(boardId) ?: error("보드가 저장되지 않음")
            saved.title to saved.user.id.value
        }
        title shouldBe "여름방학"
        ownerId shouldBe userId
    }

    // ---- createTask ----

    "createTask: 보드가 없으면 PlanBoardNotFoundException" {
        val userId = seedUser("task-noboard@test.com")
        shouldThrow<PlanBoardNotFoundException> {
            planTaskService.createTask(userId, taskRequest(planBoardId = 999))
        }
    }

    "createTask: 타인 보드면 PlanBoardForbiddenException" {
        val ownerId = seedUser("task-owner@test.com")
        val otherId = seedUser("task-other@test.com")
        val boardId = seedBoard(ownerId)

        shouldThrow<PlanBoardForbiddenException> {
            planTaskService.createTask(otherId, taskRequest(planBoardId = boardId))
        }
    }

    "createTask: 같은 보드·날짜로 2번 호출해도 daily_plan 은 1개만 생성된다 (insertIgnore)" {
        val userId = seedUser("task-race@test.com")
        val boardId = seedBoard(userId)

        planTaskService.createTask(userId, taskRequest(planBoardId = boardId, taskName = "첫번째"))
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId, taskName = "두번째"))

        val (dailyPlanCount, taskCount) = transaction(db) {
            val dailyPlan = DailyPlanRow.find {
                (DailyPlanTable.planBoardId eq boardId) and (DailyPlanTable.planDate eq LocalDate.of(2026, 7, 15))
            }.first()
            DailyPlanRow.find {
                (DailyPlanTable.planBoardId eq boardId) and (DailyPlanTable.planDate eq LocalDate.of(2026, 7, 15))
            }.count() to PlanTaskRow.find { PlanTaskTable.dailyPlanId eq dailyPlan.id }.count()
        }

        dailyPlanCount shouldBe 1
        taskCount shouldBe 2
    }

    "createTask: 태스크 이름이 150자 초과면 InvalidTaskNameException" {
        val userId = seedUser("task-name@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanTaskValidationException.InvalidTaskNameException> {
            planTaskService.createTask(userId, taskRequest(planBoardId = boardId, taskName = "a".repeat(151)))
        }
    }

    "createTask: 시간 형식이 잘못되면 InvalidTimeFormatException" {
        val userId = seedUser("task-time@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanTaskValidationException.InvalidTimeFormatException> {
            planTaskService.createTask(
                userId,
                taskRequest(planBoardId = boardId, startTime = "25:00", endTime = "19:30")
            )
        }
    }

    "createTask: 예상 소요 시간이 1분 미만이면 InvalidEstimatedMinutesException" {
        val userId = seedUser("task-min@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanTaskValidationException.InvalidEstimatedMinutesException> {
            planTaskService.createTask(userId, taskRequest(planBoardId = boardId, estimatedMinutes = 0))
        }
    }

    "createTask: 플랜보드 기간 밖 날짜면 PlanDateOutOfRangeException" {
        val userId = seedUser("task-range@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanTaskValidationException.PlanDateOutOfRangeException> {
            planTaskService.createTask(userId, taskRequest(planBoardId = boardId, planDate = "2026-12-31"))
        }
    }

    "createTask: 정상 생성 시 태스크가 저장된다" {
        val userId = seedUser("task-ok@test.com")
        val boardId = seedBoard(userId)

        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))

        val task = transaction(db) { PlanTaskRow.find { PlanTaskTable.taskName eq "수학 2단원" }.firstOrNull() }
        task?.taskName shouldBe "수학 2단원"
    }

    // ---- 조회 ----

    "getBoardDailyPlan: 보드가 없으면 PlanBoardNotFoundException" {
        val userId = seedUser("daily-noboard@test.com")
        shouldThrow<PlanBoardNotFoundException> {
            planTaskService.getBoardDailyPlan(userId, 999, LocalDate.of(2026, 7, 15))
        }
    }

    "getBoardDailyPlan: 타인 보드면 PlanBoardForbiddenException" {
        val ownerId = seedUser("daily-owner@test.com")
        val otherId = seedUser("daily-other@test.com")
        val boardId = seedBoard(ownerId)

        shouldThrow<PlanBoardForbiddenException> {
            planTaskService.getBoardDailyPlan(otherId, boardId, LocalDate.of(2026, 7, 15))
        }
    }

    "getBoardDailyPlan: 해당 날짜 일정이 없으면 빈 tasks 를 반환한다" {
        val userId = seedUser("daily-empty@test.com")
        val boardId = seedBoard(userId)

        val result = planTaskService.getBoardDailyPlan(userId, boardId, LocalDate.of(2026, 7, 20))

        result.planDate shouldBe "2026-07-20"
        result.tasks shouldBe emptyList()
    }

    "getBoardDailyPlan: 태스크를 시간순으로 반환한다" {
        val userId = seedUser("daily-order@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(
            userId,
            taskRequest(planBoardId = boardId, taskName = "늦은 과목", startTime = "19:00", endTime = "20:00")
        )
        planTaskService.createTask(
            userId,
            taskRequest(planBoardId = boardId, taskName = "이른 과목", startTime = "09:00", endTime = "10:00")
        )

        val result = planTaskService.getBoardDailyPlan(userId, boardId, LocalDate.of(2026, 7, 15))

        result.tasks.map { it.taskName } shouldBe listOf("이른 과목", "늦은 과목")
    }

    "getDailyPlan: 본인 플랜보드의 날짜별 태스크를 모두 반환한다" {
        val userId = seedUser("all@test.com")
        val boardA = seedBoard(userId)
        val boardB = seedBoard(userId)
        planTaskService.createTask(
            userId,
            taskRequest(planBoardId = boardA, taskName = "보드A 태스크", startTime = "09:00", endTime = "10:00")
        )
        planTaskService.createTask(
            userId,
            taskRequest(planBoardId = boardB, taskName = "보드B 태스크", startTime = "19:00", endTime = "20:00")
        )

        val result = planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15))

        result.tasks.map { it.taskName } shouldBe listOf("보드A 태스크", "보드B 태스크")
    }
})

private fun taskRequest(
    planBoardId: Int,
    planDate: String = "2026-07-15",
    taskName: String = "수학 2단원",
    startTime: String = "17:30",
    endTime: String = "19:30",
    estimatedMinutes: Int = 120
) = PlanTaskCreateRequest(planBoardId, planDate, taskName, startTime, endTime, estimatedMinutes)

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
