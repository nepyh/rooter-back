import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.GeneratedDailyPlan
import com.github.nepyh.rooter.module.planboard.GeneratedPlan
import com.github.nepyh.rooter.module.planboard.GeneratedPlanTask
import com.github.nepyh.rooter.module.planboard.PlanGenerationLlmClient
import com.github.nepyh.rooter.module.planboard.PlanGenerationService
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanGenerationSubjectInput
import com.github.nepyh.rooter.module.school.NiceApiClient
import com.github.nepyh.rooter.module.school.SchoolDataFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import com.github.nepyh.rooter.module.planboard.BusyTimeService
import com.github.nepyh.rooter.module.planboard.PlanBoardService
import com.github.nepyh.rooter.module.planboard.PlanTaskService
import com.github.nepyh.rooter.module.planboard.orderedChaptersInRange
import com.github.nepyh.rooter.module.planboard.orderedTextbookChapters
import com.github.nepyh.rooter.module.planboard.splitIntoChunks
import com.github.nepyh.rooter.module.planboard.topicsForChunk
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanBoardUpdateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanSubjectCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskCreateRequest
import com.github.nepyh.rooter.module.planboard.dto.PlanTaskUpdateRequest
import com.github.nepyh.rooter.module.planboard.exception.BusyTimeValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardForbiddenException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanBoardValidationException
import com.github.nepyh.rooter.module.planboard.exception.PlanSubjectNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskNotFoundException
import com.github.nepyh.rooter.module.planboard.exception.PlanTaskValidationException
import com.github.nepyh.rooter.module.planboard.model.ChapterRow
import com.github.nepyh.rooter.module.planboard.model.ChapterTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.time.LocalTime
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
            // CASCADE 로 드랍: users 를 다른 스펙(예: CatalogServiceTest)의 테이블이 FK 로 참조하고
            // 있어도 실행 순서와 무관하게 안전하게 재생성하기 위함
            exec("DROP TABLE IF EXISTS level_test_results CASCADE")
            exec("DROP TABLE IF EXISTS student_profiles CASCADE")
            exec("DROP TABLE IF EXISTS user_unavailable_times CASCADE")
            exec("DROP TABLE IF EXISTS plan_subjects CASCADE")
            exec("DROP TABLE IF EXISTS plan_tasks CASCADE")
            exec("DROP TABLE IF EXISTS daily_plans CASCADE")
            exec("DROP TABLE IF EXISTS plan_boards CASCADE")
            exec("DROP TABLE IF EXISTS chapters CASCADE")
            exec("DROP TABLE IF EXISTS textbooks CASCADE")
            exec("DROP TABLE IF EXISTS subjects CASCADE")
            exec("DROP TABLE IF EXISTS users CASCADE")
            SchemaUtils.create(
                UserTable, SubjectTable, TextbookTable, ChapterTable, PlanBoardTable, PlanSubjectTable, DailyPlanTable, PlanTaskTable,
                // plan-generation 이 등급·프로필·불가 시간을 조회한다
                LevelTestResultTable, StudentProfileTable, UnavailableTimeTable
            )
            // DDL(rooter-ddl) 의 uq_daily_plans_board_date 와 동일한 제약 — insertIgnore 레이스 방지 검증용
            exec("ALTER TABLE daily_plans ADD CONSTRAINT uq_daily_plans_board_date UNIQUE (plan_board_id, plan_date)")
        }
    }

    // 테스트 간 데이터 격리: 매 테스트 시작 전 전체 초기화 (FK 순서 주의)
    beforeEach {
        transaction(db) {
            LevelTestResultTable.deleteAll()
            StudentProfileTable.deleteAll()
            UnavailableTimeTable.deleteAll()
            PlanTaskTable.deleteAll()
            PlanSubjectTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            ChapterTable.deleteAll()
            TextbookTable.deleteAll()
            SubjectTable.deleteAll()
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

    fun seedSubject(name: String): Int = transaction(db) {
        SubjectRow.new { this.name = name }.id.value
    }

    fun seedTextbook(subjectId: Int, title: String = "테스트 교과서"): Int = transaction(db) {
        TextbookRow.new {
            subject = SubjectRow[subjectId]
            this.title = title
        }.id.value
    }

    fun seedChapter(textbookId: Int, order: Int, name: String = "${order}단원"): Int = transaction(db) {
        ChapterRow.new {
            textbook = TextbookRow[textbookId]
            chapterName = name
            chapterOrder = order
        }.id.value
    }

    /** 소단원(대단원 안에서 chapter_order 가 다시 1부터 시작하는 계층) 시드 */
    fun seedSubChapter(textbookId: Int, parentId: Int, order: Int, name: String = "${order}단원"): Int = transaction(db) {
        ChapterRow.new {
            textbook = TextbookRow[textbookId]
            this.parentId = parentId
            chapterName = name
            chapterOrder = order
        }.id.value
    }

    "createdAt: 목록·수정 응답의 createdAt 은 오프셋이 포함된 시각이다" {
        val userId = seedUser("created-at@test.com")
        val boardId = planBoardService.createBoard(userId, PlanBoardCreateRequest("보드", "2026-07-01", "2026-07-31"))

        val listed = planBoardService.getAllBoards(userId).single().createdAt
        val updated = planBoardService.updateBoard(userId, boardId, PlanBoardUpdateRequest(title = "수정")).createdAt

        listOf(listed, updated).forEach { createdAt ->
            // 오프셋이 없으면 OffsetDateTime.parse 가 실패한다
            val parsed = OffsetDateTime.parse(createdAt)
            java.time.Duration.between(parsed, OffsetDateTime.now()).abs().toMinutes() shouldBe 0L
        }
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

    // ---- updateBoard / deleteBoard ----

    "updateBoard: 본인 보드가 아니면 PlanBoardForbiddenException" {
        val ownerId = seedUser("update-owner@test.com")
        val otherId = seedUser("update-other@test.com")
        val boardId = seedBoard(ownerId)

        shouldThrow<PlanBoardForbiddenException> {
            planBoardService.updateBoard(otherId, boardId, PlanBoardUpdateRequest(title = "해킹시도"))
        }
    }

    "updateBoard: 존재하지 않는 보드면 PlanBoardNotFoundException" {
        val userId = seedUser("update-noboard@test.com")
        shouldThrow<PlanBoardNotFoundException> {
            planBoardService.updateBoard(userId, 999, PlanBoardUpdateRequest(title = "제목"))
        }
    }

    "updateBoard: title 만 전달하면 title 만 바뀐다" {
        val userId = seedUser("update-title@test.com")
        val boardId = seedBoard(userId, start = LocalDate.of(2026, 7, 1), end = LocalDate.of(2026, 7, 31))

        val response = planBoardService.updateBoard(userId, boardId, PlanBoardUpdateRequest(title = "새 제목"))

        response.title shouldBe "새 제목"
        response.startDate shouldBe "2026-07-01"
        response.endDate shouldBe "2026-07-31"
    }

    "updateBoard: 수정 후 종료일이 시작일보다 빠르면 InvalidDateRangeException" {
        val userId = seedUser("update-range@test.com")
        val boardId = seedBoard(userId, start = LocalDate.of(2026, 7, 1), end = LocalDate.of(2026, 7, 31))

        shouldThrow<PlanBoardValidationException.InvalidDateRangeException> {
            planBoardService.updateBoard(userId, boardId, PlanBoardUpdateRequest(endDate = "2026-06-01"))
        }
    }

    "deleteBoard: 본인 보드가 아니면 PlanBoardForbiddenException" {
        val ownerId = seedUser("delete-owner@test.com")
        val otherId = seedUser("delete-other@test.com")
        val boardId = seedBoard(ownerId)

        shouldThrow<PlanBoardForbiddenException> {
            planBoardService.deleteBoard(otherId, boardId)
        }
    }

    "deleteBoard: 삭제하면 하위 daily_plan/plan_task 도 함께 삭제된다" {
        val userId = seedUser("delete-cascade@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))

        planBoardService.deleteBoard(userId, boardId)

        transaction(db) {
            PlanBoardRow.findById(boardId) shouldBe null
            DailyPlanRow.find { DailyPlanTable.planBoardId eq boardId }.toList().shouldBeEmpty()
        }
    }

    // ---- addSubject / getSubjects / updateSubject / deleteSubject ----

    "addSubject: 존재하지 않는 교과서면 InvalidSubjectRangeException" {
        val userId = seedUser("subject-notextbook@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
            planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(999, 1, 1))
        }
    }

    "addSubject: 시작 단원이 끝 단원보다 뒤면 InvalidSubjectRangeException" {
        val userId = seedUser("subject-wrongorder@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("수학")
        val textbookId = seedTextbook(subjectId)
        val chapter1 = seedChapter(textbookId, 1)
        val chapter2 = seedChapter(textbookId, 2)

        shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
            planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, chapter2, chapter1))
        }
    }

    // ---- 단원 계층(대단원/소단원) 순서 ----

    "addSubject: 계층에서 트리 순서상 앞뒤인 범위는 통과한다 (대단원1 소단원 order 5 -> 대단원2 소단원 order 2)" {
        val userId = seedUser("subject-hierarchy-ok@test.com")
        val boardId = seedBoard(userId)
        val textbookId = seedTextbook(seedSubject("수학"))
        val major1 = seedChapter(textbookId, 1, "1단원")
        val major2 = seedChapter(textbookId, 2, "2단원")
        val sub15 = seedSubChapter(textbookId, major1, 5, "1-5 소단원")
        val sub21 = seedSubChapter(textbookId, major2, 1, "2-1 소단원")
        val sub22 = seedSubChapter(textbookId, major2, 2, "2-2 소단원")

        // chapter_order 만 보면 5 > 2 라 거부되던 요청 (트리 순서로는 1단원 -> 2단원)
        val created = planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, sub15, sub22))

        created.startChapterId shouldBe sub15
        created.endChapterId shouldBe sub22
    }

    "addSubject: 계층에서 트리 순서상 뒤->앞이면 InvalidSubjectRangeException" {
        val userId = seedUser("subject-hierarchy-wrongorder@test.com")
        val boardId = seedBoard(userId)
        val textbookId = seedTextbook(seedSubject("수학"))
        val major1 = seedChapter(textbookId, 1, "1단원")
        val major2 = seedChapter(textbookId, 2, "2단원")
        val sub11 = seedSubChapter(textbookId, major1, 1, "1-1 소단원")
        val sub21 = seedSubChapter(textbookId, major2, 1, "2-1 소단원")
        val sub23 = seedSubChapter(textbookId, major2, 3, "2-3 소단원")

        // chapter_order 는 3 >= 1 이지만 트리 순서로는 2단원 -> 1단원 이므로 거부
        shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
            planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, sub23, sub11))
        }
        // 같은 대단원 안에서의 order 역순도 거부
        shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
            planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, sub23, sub21))
        }
    }

    "orderedChaptersInRange: chapter_order 가 겹치는 다른 대단원의 소단원을 포함하지 않는다" {
        val textbookId = seedTextbook(seedSubject("과학"))
        val major1 = seedChapter(textbookId, 1, "1단원")
        val sub11 = seedSubChapter(textbookId, major1, 1, "1-1 소단원")
        val sub12 = seedSubChapter(textbookId, major1, 2, "1-2 소단원")
        val sub13 = seedSubChapter(textbookId, major1, 5, "1-3 소단원")
        val major2 = seedChapter(textbookId, 2, "2단원")
        val sub21 = seedSubChapter(textbookId, major2, 1, "2-1 소단원")
        val sub22 = seedSubChapter(textbookId, major2, 2, "2-2 소단원")

        fun rangeNames(startId: Int, endId: Int): List<String> = transaction(db) {
            orderedChaptersInRange(TextbookRow[textbookId], ChapterRow[startId], ChapterRow[endId])
                .map { it.chapterName }
        }

        // chapter_order in 1..2 로 뽑던 예전 로직은 2단원(대단원2)의 소단원까지 긁었다
        rangeNames(sub11, sub12) shouldBe listOf("1-1 소단원", "1-2 소단원")
        // 시작이 대단원이면 그 대단원부터, 끝이 대단원이면 그 대단원의 소단원까지 포함한다
        rangeNames(major1, sub12) shouldBe listOf("1단원", "1-1 소단원", "1-2 소단원")
        rangeNames(major1, major2) shouldBe listOf("1단원", "1-1 소단원", "1-2 소단원", "1-3 소단원", "2단원", "2-1 소단원", "2-2 소단원")
        // 소단원에서 시작하면 앞 대단원은 범위에 들어오지 않는다
        rangeNames(sub13, sub22) shouldBe listOf("1-3 소단원", "2단원", "2-1 소단원", "2-2 소단원")
    }

    "orderedChaptersInRange: 고아 parent_id 와 순환 참조가 있어도 무한 루프 없이 순서를 만든다" {
        val textbookId = seedTextbook(seedSubject("국어"))
        val major = seedChapter(textbookId, 1, "1단원")
        seedSubChapter(textbookId, major, 1, "1-1 소단원")
        val orphan = seedSubChapter(textbookId, 999999, 2, "고아 소단원") // 없는 부모를 가리키는 행
        val cycleX = seedChapter(textbookId, 9, "순환 X")
        val cycleY = seedSubChapter(textbookId, cycleX, 1, "순환 Y")
        transaction(db) { ChapterRow[cycleX].parentId = cycleY } // X -> Y, Y -> X 순환

        val ordered = transaction(db) { orderedTextbookChapters(textbookId) }
        val ids = ordered.map { it.id.value }

        ids.size shouldBe 5                      // 누락 없이 전부 포함
        ids.toSet().size shouldBe ids.size       // 중복 없이 한 번씩만
        ids.contains(orphan) shouldBe true
        ids.contains(cycleX) shouldBe true
        ids.contains(cycleY) shouldBe true
    }

    "addSubject: 계층이 있어도 다른 교과서의 소단원이 섞이면 InvalidSubjectRangeException" {
        val userId = seedUser("subject-hierarchy-other@test.com")
        val boardId = seedBoard(userId)
        val science = seedTextbook(seedSubject("과학"), title = "과학")
        val history = seedTextbook(seedSubject("역사"), title = "역사")
        val scienceMajor = seedChapter(science, 1, "1단원")
        val scienceSub = seedSubChapter(science, scienceMajor, 5, "1-5 소단원")
        val historyMajor = seedChapter(history, 1, "1단원")
        val historySub = seedSubChapter(history, historyMajor, 1, "1-1 소단원")

        listOf(scienceSub to historySub, historySub to scienceSub).forEach { (start, end) ->
            shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
                planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(science, start, end))
            }
        }
    }

    "addSubject·updateSubject: 다른 교과서의 단원이 섞이면 InvalidSubjectRangeException" {
        val userId = seedUser("subject-othertextbook@test.com")
        val boardId = seedBoard(userId)
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val english = seedTextbook(seedSubject("영어"), title = "영어")
        val math1 = seedChapter(math, 1)
        val math2 = seedChapter(math, 2)
        val english1 = seedChapter(english, 1)
        val english2 = seedChapter(english, 2)

        listOf(english1 to english2, math1 to english2, english1 to math2).forEach { (start, end) ->
            shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
                planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(math, start, end))
            }
        }

        val created = planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(math, math1, math2))
        shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
            planBoardService.updateSubject(userId, boardId, created.id, PlanSubjectCreateRequest(math, math1, english2))
        }
    }

    "plan-generation: 다른 교과서의 단원이 섞이면 AI 호출 전에 InvalidSubjectRangeException" {
        val userId = seedUser("gen-othertextbook@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val english = seedTextbook(seedSubject("영어"), title = "영어")
        val math1 = seedChapter(math, 1)
        val english2 = seedChapter(english, 2)
        // 범위 검증에서 먼저 실패하므로 LLM·NICE 는 호출되지 않는다
        val service = PlanGenerationService(
            PlanGenerationLlmClient(dummyAppConfig()),
            SchoolDataFetcher(NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") })))
        )

        shouldThrow<PlanBoardValidationException.InvalidSubjectRangeException> {
            service.generate(
                userId,
                PlanGenerationRequest(
                    title = "시험 대비",
                    subjects = listOf(PlanGenerationSubjectInput(math, math1, english2)),
                    startDate = "2026-07-01",
                    examDate = "2026-07-10"
                )
            )
        }
    }

    "plan-generation: 14일을 넘으면 비슷한 길이의 구간으로 나눈다" {
        splitIntoChunks(14) shouldBe listOf(1..14)
        splitIntoChunks(15) shouldBe listOf(1..8, 9..15)
        splitIntoChunks(30) shouldBe listOf(1..10, 11..20, 21..30)
        splitIntoChunks(60).map { it.count() } shouldBe listOf(12, 12, 12, 12, 12)
    }

    "plan-generation: 단원은 구간 비율만큼 나눠 주고, 단원이 적으면 구간끼리 겹쳐 쓴다" {
        val topics = listOf("1", "2", "3", "4", "5", "6")
        topicsForChunk(topics, 1..10, 30) shouldBe listOf("1", "2")
        topicsForChunk(topics, 11..20, 30) shouldBe listOf("3", "4")
        topicsForChunk(topics, 21..30, 30) shouldBe listOf("5", "6")
        topicsForChunk(listOf("1"), 11..20, 30) shouldBe listOf("1")
    }

    "plan-generation: 30일 계획은 3구간으로 나눠 생성하고 30일치를 모두 저장한다" {
        val userId = seedUser("gen-chunk@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val math2 = seedChapter(math, 2)
        val llm = FakePlanGenerationLlmClient()
        val service = PlanGenerationService(llm, noNiceSchoolDataFetcher())

        val response = service.generate(
            userId,
            PlanGenerationRequest(
                title = "긴 계획",
                subjects = listOf(PlanGenerationSubjectInput(math, math1, math2)),
                startDate = "2026-07-01",
                daysRemaining = 30
            )
        )

        llm.contexts.size shouldBe 3
        llm.contexts.all { it.contains("총 학습 기간: 10일") } shouldBe true
        response.dailyPlans.map { it.date } shouldBe (0L until 30L).map { LocalDate.of(2026, 7, 1).plusDays(it).toString() }
        transaction(db) { DailyPlanRow.find { DailyPlanTable.planBoardId eq response.planBoardId }.count() } shouldBe 30L
    }

    "plan-generation: 일수가 모자라게 오면 한 번 더 요청해서 채운다" {
        val userId = seedUser("gen-retry@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val llm = FakePlanGenerationLlmClient(shortResponses = 1)
        val service = PlanGenerationService(llm, noNiceSchoolDataFetcher())

        val response = service.generate(
            userId,
            PlanGenerationRequest(
                title = "재시도",
                subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)),
                startDate = "2026-07-01",
                daysRemaining = 7
            )
        )

        llm.contexts.size shouldBe 2
        response.dailyPlans.size shouldBe 7
    }

    "plan-generation: 다시 요청해도 모자라면 일부만 저장하지 않고 GenerationFailedException" {
        val userId = seedUser("gen-short@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val llm = FakePlanGenerationLlmClient(shortResponses = Int.MAX_VALUE)
        val service = PlanGenerationService(llm, noNiceSchoolDataFetcher())

        shouldThrow<PlanBoardValidationException.GenerationFailedException> {
            service.generate(
                userId,
                PlanGenerationRequest(
                    title = "실패",
                    subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)),
                    startDate = "2026-07-01",
                    daysRemaining = 7
                )
            )
        }
        llm.contexts.size shouldBe 2
        transaction(db) { PlanBoardRow.find { PlanBoardTable.userId eq userId }.count() } shouldBe 0L
    }

    "busy-times: 등교일은 하교 전·취침·등록한 불가능 시간을, 주말은 취침·기존 할일만 막고 나머지를 빈 시간으로 준다" {
        val userId = seedUser("busy@test.com")
        val otherUserId = seedUser("busy-other@test.com")
        transaction(db) {
            UnavailableTimeRow.new {
                user = UserRow[userId]; dayOfWeek = DayOfWeek.FRIDAY
                startTime = LocalTime.of(18, 0); endTime = LocalTime.of(20, 0)
            }
        }
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(boardId, planDate = "2026-07-04", taskName = "영어 단어", startTime = "10:00", endTime = "11:00", estimatedMinutes = 60))
        planTaskService.createTask(otherUserId, taskRequest(seedBoard(otherUserId), planDate = "2026-07-04", startTime = "12:00", endTime = "13:00", estimatedMinutes = 60))

        // 2026-07-03 금요일(학교 정보 없음 → 평일은 등교일, 하교 16:30), 07-04 토요일
        val response = BusyTimeService(noNiceSchoolDataFetcher()).getBusyTimes(userId, LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 4))

        val (friday, saturday) = response.days
        friday.isSchoolDay shouldBe true
        friday.busyTimes.map { "${it.type} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "SLEEP 00:00~06:30", "SCHOOL 00:00~16:30", "UNAVAILABLE 18:00~20:00", "SLEEP 23:00~24:00"
        )
        friday.freeTimes.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("16:30~18:00", "20:00~23:00")

        saturday.isSchoolDay shouldBe false
        saturday.busyTimes.map { "${it.type} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "SLEEP 00:00~06:30", "TASK 10:00~11:00", "SLEEP 23:00~24:00"
        )
        saturday.busyTimes[1].let {
            it.taskName shouldBe "영어 단어"
            it.planBoardId shouldBe boardId
        }
        saturday.freeTimes.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("06:30~10:00", "11:00~23:00")
    }

    "busy-times: endDate 가 startDate 보다 빠르거나 31일을 넘으면 InvalidDateRangeException" {
        val userId = seedUser("busy-range@test.com")
        val service = BusyTimeService(noNiceSchoolDataFetcher())

        shouldThrow<BusyTimeValidationException.InvalidDateRangeException> {
            service.getBusyTimes(userId, LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1))
        }
        shouldThrow<BusyTimeValidationException.InvalidDateRangeException> {
            service.getBusyTimes(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))
        }
        service.getBusyTimes(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)).days.size shouldBe 31
    }

    "plan-generation: 다른 플랜보드에 이미 있는 할일과 겹치지 않게, 쉬는 시간 10분을 두고 배치한다" {
        val userId = seedUser("gen-other-board@test.com")
        val otherUserId = seedUser("gen-other-user@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val saturday = LocalDate.of(2026, 7, 4) // 주말이라 06:30 부터 빈 시간
        // 같은 유저의 다른 보드: 06:30~07:30 / 다른 유저: 07:40~09:00 (영향 없어야 함)
        planTaskService.createTask(userId, taskRequest(seedBoard(userId), planDate = "2026-07-04", startTime = "06:30", endTime = "07:30", estimatedMinutes = 60))
        planTaskService.createTask(otherUserId, taskRequest(seedBoard(otherUserId), planDate = "2026-07-04", startTime = "07:40", endTime = "09:00", estimatedMinutes = 80))
        val llm = FakePlanGenerationLlmClient(tasks = listOf(GeneratedPlanTask("수학 개념", 60), GeneratedPlanTask("수학 문제", 30)))

        val response = PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(
                title = "새 보드",
                subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)),
                startDate = saturday.toString(),
                daysRemaining = 1
            )
        )

        response.dailyPlans.single().tasks.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("07:40~08:40", "08:50~09:20")
    }

    "addSubject: 정상 등록되면 subjectName/textbookTitle 이 채워진다" {
        val userId = seedUser("subject-ok@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("영어")
        val textbookId = seedTextbook(subjectId, title = "능률영어")
        val chapter1 = seedChapter(textbookId, 1)
        val chapter2 = seedChapter(textbookId, 2)

        val response = planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, chapter1, chapter2))

        response.subjectName shouldBe "영어"
        response.textbookTitle shouldBe "능률영어"
        response.planBoardId shouldBe boardId
    }

    "getSubjects: 등록한 과목 범위 목록을 반환한다" {
        val userId = seedUser("subject-list@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("국어")
        val textbookId = seedTextbook(subjectId)
        val chapter1 = seedChapter(textbookId, 1)
        val chapter2 = seedChapter(textbookId, 2)
        planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, chapter1, chapter2))

        val result = planBoardService.getSubjects(userId, boardId)

        result.map { it.subjectName } shouldBe listOf("국어")
    }

    "updateSubject: 존재하지 않는 subjectId 면 PlanSubjectNotFoundException" {
        val userId = seedUser("subject-updatenone@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("사회")
        val textbookId = seedTextbook(subjectId)
        val chapter1 = seedChapter(textbookId, 1)
        val chapter2 = seedChapter(textbookId, 2)

        shouldThrow<PlanSubjectNotFoundException> {
            planBoardService.updateSubject(userId, boardId, 999, PlanSubjectCreateRequest(textbookId, chapter1, chapter2))
        }
    }

    "updateSubject: 정상 수정된다" {
        val userId = seedUser("subject-update@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("과학")
        val textbookId = seedTextbook(subjectId)
        val chapter1 = seedChapter(textbookId, 1)
        val chapter2 = seedChapter(textbookId, 2)
        val created = planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, chapter1, chapter1))

        val updated = planBoardService.updateSubject(
            userId, boardId, created.id,
            PlanSubjectCreateRequest(textbookId, chapter1, chapter2, customRangeText = "심화")
        )

        updated.endChapterId shouldBe chapter2
        updated.customRangeText shouldBe "심화"
    }

    "deleteSubject: 삭제 후 조회하면 빈 목록" {
        val userId = seedUser("subject-delete@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("역사")
        val textbookId = seedTextbook(subjectId)
        val chapter1 = seedChapter(textbookId, 1)
        val created = planBoardService.addSubject(userId, boardId, PlanSubjectCreateRequest(textbookId, chapter1, chapter1))

        planBoardService.deleteSubject(userId, boardId, created.id)

        planBoardService.getSubjects(userId, boardId).shouldBeEmpty()
    }

    "deleteSubject: 존재하지 않으면 PlanSubjectNotFoundException" {
        val userId = seedUser("subject-deletenone@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanSubjectNotFoundException> {
            planBoardService.deleteSubject(userId, boardId, 999)
        }
    }

    // ---- updateTask / deleteTask ----

    "updateTask: 존재하지 않거나 본인 소유가 아니면 PlanTaskNotFoundException" {
        val userId = seedUser("task-updatenone@test.com")
        shouldThrow<PlanTaskNotFoundException> {
            planTaskService.updateTask(userId, 999, PlanTaskUpdateRequest(taskName = "수정"))
        }
    }

    "updateTask: 이름만 전달하면 이름만 바뀐다" {
        val userId = seedUser("task-updatename@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))
        val taskId = transaction(db) { PlanTaskRow.find { PlanTaskTable.taskName eq "수학 2단원" }.first().id.value }

        val response = planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수학 3단원"))

        response.taskName shouldBe "수학 3단원"
        response.startTime shouldBe "17:30"
    }

    "updateTask: 수정 후 endTime 이 startTime 보다 빠르거나 같으면 InvalidTimeRangeException" {
        val userId = seedUser("task-updaterange@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId, startTime = "17:00", endTime = "19:00"))
        val taskId = transaction(db) { PlanTaskRow.find { PlanTaskTable.taskName eq "수학 2단원" }.first().id.value }

        shouldThrow<PlanTaskValidationException.InvalidTimeRangeException> {
            planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(startTime = "20:00"))
        }
    }

    "deleteTask: 삭제 후 조회되지 않는다" {
        val userId = seedUser("task-delete@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))
        val taskId = transaction(db) { PlanTaskRow.find { PlanTaskTable.taskName eq "수학 2단원" }.first().id.value }

        planTaskService.deleteTask(userId, taskId)

        transaction(db) { PlanTaskRow.findById(taskId) shouldBe null }
    }

    "deleteTask: 본인 소유가 아니면 PlanTaskNotFoundException" {
        val ownerId = seedUser("task-delete-owner@test.com")
        val otherId = seedUser("task-delete-other@test.com")
        val boardId = seedBoard(ownerId)
        planTaskService.createTask(ownerId, taskRequest(planBoardId = boardId))
        val taskId = transaction(db) { PlanTaskRow.find { PlanTaskTable.taskName eq "수학 2단원" }.first().id.value }

        shouldThrow<PlanTaskNotFoundException> {
            planTaskService.deleteTask(otherId, taskId)
        }
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

    "createTask: 종료 시간이 시작 시간보다 빠르거나 같으면 InvalidTimeRangeException" {
        val userId = seedUser("task-range@test.com")
        val boardId = seedBoard(userId)

        shouldThrow<PlanTaskValidationException.InvalidTimeRangeException> {
            planTaskService.createTask(
                userId,
                taskRequest(planBoardId = boardId, startTime = "19:30", endTime = "19:30")
            )
        }
        shouldThrow<PlanTaskValidationException.InvalidTimeRangeException> {
            planTaskService.createTask(
                userId,
                taskRequest(planBoardId = boardId, startTime = "19:30", endTime = "17:00")
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

    fun dailyPlanIdOf(boardId: Int, date: LocalDate): Int = transaction(db) {
        DailyPlanRow.find { (DailyPlanTable.planBoardId eq boardId) and (DailyPlanTable.planDate eq date) }
            .single().id.value
    }

    "dailyPlanId: 여러 보드를 합치는 일간·주간 조회는 태스크마다 자기 보드의 dailyPlanId 를 주고 최상위는 null" {
        val userId = seedUser("dp-id@test.com")
        val boardA = seedBoard(userId)
        val boardB = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardA, taskName = "A", startTime = "09:00", endTime = "10:00"))
        planTaskService.createTask(userId, taskRequest(planBoardId = boardB, taskName = "B", startTime = "19:00", endTime = "20:00"))
        val date = LocalDate.of(2026, 7, 15)
        val expected = listOf("A" to dailyPlanIdOf(boardA, date), "B" to dailyPlanIdOf(boardB, date))

        val daily = planTaskService.getDailyPlan(userId, date)
        daily.dailyPlanId shouldBe null
        daily.tasks.map { it.taskName to it.dailyPlanId } shouldBe expected

        val weekDay = planTaskService.getWeeklyPlan(userId, date).days.single { it.planDate == "2026-07-15" }
        weekDay.dailyPlanId shouldBe null
        weekDay.tasks.map { it.taskName to it.dailyPlanId } shouldBe expected
    }

    "dailyPlanId: 보드 하나 기준 일간 조회는 최상위와 태스크 모두 그 보드의 dailyPlanId 를 준다" {
        val userId = seedUser("dp-board@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))
        val date = LocalDate.of(2026, 7, 15)
        val dailyPlanId = dailyPlanIdOf(boardId, date)

        val result = planTaskService.getBoardDailyPlan(userId, boardId, date)

        result.dailyPlanId shouldBe dailyPlanId
        result.tasks.map { it.dailyPlanId } shouldBe listOf(dailyPlanId)
        // 일일 계획이 없는 날짜는 null
        planTaskService.getBoardDailyPlan(userId, boardId, LocalDate.of(2026, 7, 20)).dailyPlanId shouldBe null
    }

    "dailyPlanId: 태스크 수정·완료 응답에도 dailyPlanId 가 들어간다" {
        val userId = seedUser("dp-update@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))
        val dailyPlanId = dailyPlanIdOf(boardId, LocalDate.of(2026, 7, 15))
        val taskId = planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15)).tasks.single().id

        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수정됨")).dailyPlanId shouldBe dailyPlanId
        planTaskService.completeTask(userId, taskId, true).dailyPlanId shouldBe dailyPlanId
    }

})

// context 의 "총 학습 기간: N일" 만큼 계획을 돌려준다. shortResponses 번째까지는 절반만 돌려준다.
private class FakePlanGenerationLlmClient(
    private var shortResponses: Int = 0,
    private val tasks: List<GeneratedPlanTask> = emptyList()
) : PlanGenerationLlmClient(dummyAppConfig()) {
    val contexts = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun generatePlan(context: String): GeneratedPlan {
        contexts += context
        val days = Regex("총 학습 기간: (\\d+)일").find(context)!!.groupValues[1].toInt()
        val returned = synchronized(this) { if (shortResponses > 0) { shortResponses--; days / 2 } else days }
        return GeneratedPlan(
            daily_plans = (1..returned).map { GeneratedDailyPlan(day = it, topics = listOf("주제"), goal = "목표", tasks = tasks) },
            tips = listOf("팁")
        )
    }
}

private fun noNiceSchoolDataFetcher() =
    SchoolDataFetcher(NiceApiClient(apiKey = "test-key", httpClient = HttpClient(MockEngine { error("NICE 가 호출되면 안 됨") })))

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
