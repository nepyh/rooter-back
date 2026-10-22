import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.planboard.GeneratedDailyPlan
import com.github.nepyh.rooter.module.planboard.GeneratedPlan
import com.github.nepyh.rooter.module.planboard.GeneratedPlanTask
import com.github.nepyh.rooter.module.planboard.PlanGenerationLlmClient
import com.github.nepyh.rooter.module.planboard.PlanGenerationService
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerRow
import com.github.nepyh.rooter.module.studystyle.model.StudyStyleAnswerTable
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
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanSubjectTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectRow
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.planboard.model.TextbookRow
import com.github.nepyh.rooter.module.planboard.model.TextbookTable
import com.github.nepyh.rooter.module.leveltest.model.LevelTestResultTable
import com.github.nepyh.rooter.module.taskquiz.model.TaskQuizAttemptTable
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.UnavailableKind
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
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
            exec("DROP TABLE IF EXISTS task_quiz_attempts CASCADE")
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
                LevelTestResultTable, StudentProfileTable, UnavailableTimeTable, StudyStyleAnswerTable,
                // 태스크 완료 처리가 퀴즈로 확정된 태스크인지 조회한다
                TaskQuizAttemptTable
            )
            // DDL(rooter-ddl) 의 uq_daily_plans_board_date 와 동일한 제약 — insertIgnore 레이스 방지 검증용
            exec("ALTER TABLE daily_plans ADD CONSTRAINT uq_daily_plans_board_date UNIQUE (plan_board_id, plan_date)")
        }
    }

    // 테스트 간 데이터 격리: 매 테스트 시작 전 전체 초기화 (FK 순서 주의)
    beforeEach {
        transaction(db) {
            LevelTestResultTable.deleteAll()
            StudyStyleAnswerTable.deleteAll()
            StudentProfileTable.deleteAll()
            UnavailableTimeTable.deleteAll()
            TaskQuizAttemptTable.deleteAll()
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
        end: LocalDate = LocalDate.of(2026, 7, 31),
        examDate: LocalDate? = null
    ): Int = transaction(db) {
        PlanBoardRow.new {
            user = UserRow[userId]
            title = "테스트 보드"
            this.startDate = start
            this.endDate = end
            this.examDate = examDate
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

    "busy-times: 등교일은 하교 전·귀가·취침·식사·등록한 불가능 시간을, 주말은 취침·식사·기존 할일만 막고 나머지를 빈 시간으로 준다" {
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
        // 귀가 기본 40분, 식사 기본값(아침 기상 후 30분·점심 12:00~13:00·저녁 18:00~19:00)
        friday.busyTimes.map { "${it.type} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "SLEEP 00:00~06:30", "SCHOOL 00:00~16:30", "MEAL 06:30~07:00", "MEAL 12:00~13:00", "COMMUTE 16:30~17:10",
            "MEAL 18:00~19:00", "UNAVAILABLE 18:00~20:00", "SLEEP 23:00~24:00"
        )
        friday.freeTimes.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("17:10~18:00", "20:00~23:00")

        saturday.isSchoolDay shouldBe false
        saturday.busyTimes.map { "${it.type} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "SLEEP 00:00~06:30", "MEAL 06:30~07:00", "TASK 10:00~11:00", "MEAL 12:00~13:00", "MEAL 18:00~19:00", "SLEEP 23:00~24:00"
        )
        saturday.busyTimes[2].let {
            it.taskName shouldBe "영어 단어"
            it.planBoardId shouldBe boardId
        }
        saturday.freeTimes.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("07:00~10:00", "11:00~12:00", "13:00~18:00", "19:00~23:00")
    }

    "busy-times: 사용자가 저장한 수면시간(01:00 취침, 07:30 기상)을 SLEEP 으로 주고 기본 23:00~06:30 은 막지 않는다" {
        val userId = seedUser("busy-sleep@test.com")
        transaction(db) { UserRow[userId].apply { sleepTime = LocalTime.of(1, 0); wakeTime = LocalTime.of(7, 30) } }

        // 2026-07-04 토요일
        val saturday = BusyTimeService(noNiceSchoolDataFetcher()).getBusyTimes(userId, LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 4)).days.single()

        // 아침 기본값은 저장한 기상 시각(07:30) 기준
        saturday.busyTimes.map { "${it.type} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "SLEEP 01:00~07:30", "MEAL 07:30~08:00", "MEAL 12:00~13:00", "MEAL 18:00~19:00"
        )
        saturday.freeTimes.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("00:00~01:00", "08:00~12:00", "13:00~18:00", "19:00~24:00")
    }

    "busy-times: 저장한 귀가 시간과 그 요일에 등록한 식사를 기본값 대신 쓴다" {
        val userId = seedUser("busy-routine@test.com")
        transaction(db) {
            UserRow[userId].commuteMinutes = 20
            UnavailableTimeRow.new {
                user = UserRow[userId]; dayOfWeek = DayOfWeek.FRIDAY; kind = UnavailableKind.DINNER; label = "가족 저녁"
                startTime = LocalTime.of(19, 0); endTime = LocalTime.of(19, 40)
            }
            UnavailableTimeRow.new {
                user = UserRow[userId]; dayOfWeek = DayOfWeek.FRIDAY; kind = UnavailableKind.ACADEMY
                startTime = LocalTime.of(20, 0); endTime = LocalTime.of(21, 0)
            }
        }

        // 2026-07-03 금요일(등교일, 하교 16:30)
        val friday = BusyTimeService(noNiceSchoolDataFetcher()).getBusyTimes(userId, LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 3)).days.single()

        friday.busyTimes.map { "${it.type} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "SLEEP 00:00~06:30", "SCHOOL 00:00~16:30", "MEAL 06:30~07:00", "MEAL 12:00~13:00", "COMMUTE 16:30~16:50",
            "MEAL 19:00~19:40", "UNAVAILABLE 20:00~21:00", "SLEEP 23:00~24:00"
        )
        friday.freeTimes.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("16:50~19:00", "19:40~20:00", "21:00~23:00")
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

    "plan-generation: 공부 스타일 설문(밤·짧고 굵게)이 있으면 잠들기 전부터 5분씩 쉬며 배치한다" {
        val userId = seedUser("gen-style@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        transaction(db) {
            // Q1 = 1 (짧고 굵게, 5분 휴식), Q2 = 3 (밤)
            listOf(1 to 1, 2 to 3).forEach { (question, option) ->
                StudyStyleAnswerRow.new {
                    user = UserRow[userId]
                    questionNumber = question.toShort()
                    answerOption = option.toShort()
                }
            }
        }
        val llm = FakePlanGenerationLlmClient(tasks = listOf(GeneratedPlanTask("수학 개념", 60), GeneratedPlanTask("수학 문제", 30)))

        val response = PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(
                title = "밤 공부",
                subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)),
                startDate = "2026-07-01", // 수요일 등교일: 빈 시간 16:30~23:00
                daysRemaining = 1
            )
        )

        response.dailyPlans.single().tasks.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("21:25~22:25", "22:30~23:00")
        llm.studyStyleRules.single() shouldContain "task 하나의 estimated_minutes 는 30분을 넘기지 마라"
        llm.studyStyleRules.single() shouldContain "공부가 잘 되는 시간대: 밤 (자기 전)"
    }

    "plan-generation: 설문이 없으면 AI 에 공부 스타일 지시를 넘기지 않는다" {
        val userId = seedUser("gen-no-style@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val llm = FakePlanGenerationLlmClient()

        PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(title = "설문 없음", subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)), startDate = "2026-07-01", daysRemaining = 1)
        )

        llm.studyStyleRules.single() shouldBe ""
    }

    "plan-generation: 주말이 변수인 학생에게는 구간마다 그 구간 기준 주말 day 번호를 알려준다" {
        val userId = seedUser("gen-weekend@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        transaction(db) {
            StudyStyleAnswerRow.new {
                user = UserRow[userId]
                questionNumber = 4
                answerOption = 3 // 주말 전체
            }
        }
        val llm = FakePlanGenerationLlmClient()

        // 2026-07-01(수) 부터 30일 → 10일씩 3구간. 1구간 주말 = 07-04·05 (day 4, 5)
        PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(title = "주말", subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)), startDate = "2026-07-01", daysRemaining = 30)
        )

        llm.studyStyleRules.size shouldBe 3
        llm.studyStyleRules.any { it.contains("주말(day 4, 5)") } shouldBe true
        // 2구간(07-11~07-20) 주말 = 07-11·12·18·19 → day 1, 2, 8, 9
        llm.studyStyleRules.any { it.contains("주말(day 1, 2, 8, 9)") } shouldBe true
    }

    // 2026-10-05 은 월요일(등교일, 학교 정보 없음 → 하교 16:30), 2026-10-03 은 토요일
    fun kstClock(dateTime: String): Clock = Clock.fixed(java.time.LocalDateTime.parse(dateTime).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(), java.time.ZoneOffset.UTC)

    suspend fun generateToday(email: String, clock: Clock, startDate: String? = null, days: Int = 1): Pair<com.github.nepyh.rooter.module.planboard.dto.PlanGenerationResponse, FakePlanGenerationLlmClient> {
        val userId = seedUser(email)
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val llm = FakePlanGenerationLlmClient(tasks = listOf(GeneratedPlanTask("수학 개념", 60), GeneratedPlanTask("수학 문제", 30)))
        val response = PlanGenerationService(llm, noNiceSchoolDataFetcher(), clock).generate(
            userId,
            PlanGenerationRequest(title = "오늘 시작", subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)), startDate = startDate, daysRemaining = days)
        )
        return response to llm
    }

    "plan-generation: 평일 저녁 8시 28분에 오늘부터 만들면 오늘 할일은 지금 이후(20:30)부터 잡히고, AI 에 남은 시간을 알려준다" {
        val (response, llm) = generateToday("now-weekday@test.com", kstClock("2026-10-05T20:28"), days = 2)

        response.dailyPlans.first().date shouldBe "2026-10-05"
        response.dailyPlans.first().tasks.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("20:30~21:30", "21:40~22:10")
        // 내일은 평소대로 — 귀가 뒤 17:10~18:00 엔 60분 할일이 안 들어가 저녁(18:00~19:00) 뒤로
        response.dailyPlans[1].tasks.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("19:00~20:00", "20:10~20:40")
        // 오늘은 20:30~23:00 (150분), 내일(화)은 귀가 뒤 17:10~18:00 (50분) + 저녁 뒤 19:00~23:00 (240분)
        llm.contexts.single().contains("- day 1 (2026-10-05 월, 오늘이라 지금 20:28 이후만): 공부 가능 150분, 가장 긴 연속 150분, 권장 90~150분") shouldBe true
        llm.contexts.single().contains("- day 2 (2026-10-06 화): 공부 가능 290분, 가장 긴 연속 240분, 권장 90~150분") shouldBe true
    }

    "plan-generation: 주말 오후 4시에 오늘부터 만들면 아침(06:30)이 아니라 지금(16:00)부터 잡힌다" {
        val (response, _) = generateToday("now-weekend@test.com", kstClock("2026-10-03T16:00"))

        response.dailyPlans.single().tasks.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("16:00~17:00", "17:10~17:40")
    }

    "plan-generation: 내일부터 시작하는 계획은 지금 시각과 상관없이 평소대로 잡히고, 공부 가능 시간도 하루 전체 기준이다" {
        val (response, llm) = generateToday("now-tomorrow@test.com", kstClock("2026-10-05T20:28"), startDate = "2026-10-06")

        response.dailyPlans.single().tasks.map { "${it.startTime}~${it.endTime}" } shouldBe listOf("19:00~20:00", "20:10~20:40")
        llm.contexts.single().contains("오늘이라 지금") shouldBe false
        llm.contexts.single().contains("- day 1 (2026-10-06 화): 공부 가능 290분, 가장 긴 연속 240분, 권장 90~150분") shouldBe true
    }

    "plan-generation: 밤 늦게(22:50) 만들어 오늘 남은 시간이 부족하면 오늘 할일은 비고, 다음 날부터 잡힌다" {
        val (response, llm) = generateToday("now-late@test.com", kstClock("2026-10-05T22:50"), days = 2)

        response.dailyPlans.first().tasks shouldBe emptyList()
        response.dailyPlans[1].tasks.size shouldBe 2
        llm.contexts.single().contains("- day 1 (2026-10-05 월, 오늘이라 지금 22:50 이후만): 공부 가능 10분, 가장 긴 연속 10분, 권장 10~10분") shouldBe true
    }

    "plan-generation: 날짜마다 학원 같은 불가능 시간·다른 할일을 뺀 공부 가능 시간과 가장 긴 연속 시간, 쉬는 시간을 AI 에 알려준다" {
        val userId = seedUser("gen-capacity@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        transaction(db) {
            // 수요일 학원 18:00~20:00 → 귀가 뒤 17:10~23:00 이 17:10~18:00(50분) + 20:00~23:00(180분) 으로 쪼개진다 (저녁 18:00~19:00 은 학원에 포함)
            UnavailableTimeRow.new {
                user = UserRow[userId]; dayOfWeek = DayOfWeek.WEDNESDAY
                startTime = LocalTime.of(18, 0); endTime = LocalTime.of(20, 0)
            }
        }
        val llm = FakePlanGenerationLlmClient()

        // 2026-07-01 수 ~ 07-04 토 (학교 정보 없음 → 평일 하교 16:30, 주말은 06:30 부터)
        PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(title = "분량", subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)), startDate = "2026-07-01", daysRemaining = 4)
        )

        val context = llm.contexts.single()
        context.contains("할일 사이에는 쉬는 시간 10분이 들어간다") shouldBe true
        context.contains("- day 1 (2026-07-01 수): 공부 가능 230분, 가장 긴 연속 180분, 권장 90~150분") shouldBe true
        // 목: 17:10~18:00 + 19:00~23:00, 토: 아침·점심·저녁 사이 07:00~12:00 + 13:00~18:00 + 19:00~23:00
        context.contains("- day 2 (2026-07-02 목): 공부 가능 290분, 가장 긴 연속 240분, 권장 90~150분") shouldBe true
        context.contains("- day 4 (2026-07-04 토): 공부 가능 840분, 가장 긴 연속 300분, 권장 150~240분") shouldBe true
    }

    "plan-generation: 벼락치기 모드면 하루 권장 공부량을 1.5배로 알려준다" {
        val userId = seedUser("gen-cram-target@test.com")
        val math = seedTextbook(seedSubject("수학"), title = "수학")
        val math1 = seedChapter(math, 1)
        val llm = FakePlanGenerationLlmClient()

        PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(title = "벼락치기", subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)), startDate = "2026-07-03", daysRemaining = 2, isCramMode = true)
        )

        llm.contexts.single().contains("- day 1 (2026-07-03 금): 공부 가능 290분, 가장 긴 연속 240분, 권장 135~225분") shouldBe true
        llm.contexts.single().contains("- day 2 (2026-07-04 토): 공부 가능 840분, 가장 긴 연속 300분, 권장 225~360분") shouldBe true
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

    "updateTask: 시간만 바꾸면 예상 시간을 바뀐 길이로 맞추고, 같이 보내면 보낸 값을 쓴다" {
        val userId = seedUser("task-update-estimate@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId)) // 17:30~19:30, 120분
        val taskId = transaction(db) { PlanTaskRow.find { PlanTaskTable.taskName eq "수학 2단원" }.first().id.value }

        // 끝 시각만 당겨 30분으로 줄임 → 예상 시간도 30분 (퀴즈 문항 수·캘린더 합계 기준)
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(endTime = "18:00")).estimatedMinutes shouldBe 30
        // 위치만 옮김 (길이 그대로) → 30분 유지
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(startTime = "20:00", endTime = "20:30")).estimatedMinutes shouldBe 30
        // 시간과 예상 시간을 같이 보내면 보낸 값
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(startTime = "20:00", endTime = "21:00", estimatedMinutes = 50)).estimatedMinutes shouldBe 50
        // 이름만 바꾸면 예상 시간은 그대로
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수학 3단원")).estimatedMinutes shouldBe 50
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

    "dailyPlanId: 태스크 수정(상태 변경 포함) 응답에도 dailyPlanId 가 들어간다" {
        val userId = seedUser("dp-update@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))
        val dailyPlanId = dailyPlanIdOf(boardId, LocalDate.of(2026, 7, 15))
        val taskId = planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15)).tasks.single().id

        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수정됨")).dailyPlanId shouldBe dailyPlanId
        // 상태 변경(PENDING → FAILED)도 단건 응답이라 같은 값이 실린다
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.FAILED))
            .dailyPlanId shouldBe dailyPlanId
    }

    // ---- 할일이 속한 플랜보드 (여러 보드가 섞이는 조회에서 보드 구분) ----

    "planBoardId: 보드가 섞이는 조회에서 태스크마다 자기 보드 id 가 실린다" {
        val userId = seedUser("task-planboard@test.com")
        val boardA = seedBoard(userId)
        val boardB = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardA, taskName = "A", startTime = "09:00", endTime = "10:00"))
        planTaskService.createTask(userId, taskRequest(planBoardId = boardB, taskName = "B", startTime = "19:00", endTime = "20:00"))
        val date = LocalDate.of(2026, 7, 15)
        val expected = listOf("A" to boardA, "B" to boardB)

        planTaskService.getDailyPlan(userId, date).tasks.map { it.taskName to it.planBoardId } shouldBe expected

        val weekDay = planTaskService.getWeeklyPlan(userId, date).days.single { it.planDate == "2026-07-15" }
        weekDay.tasks.map { it.taskName to it.planBoardId } shouldBe expected

        // 보드 하나 기준 조회는 그 보드 id 만 나온다
        planTaskService.getBoardDailyPlan(userId, boardB, date).tasks.map { it.planBoardId } shouldBe listOf(boardB)
    }

    "planBoardId: 수정(상태 변경 포함) 단건 응답에도 보드 id 가 들어간다" {
        val userId = seedUser("task-planboard-update@test.com")
        val boardId = seedBoard(userId)
        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))
        val taskId = planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15)).tasks.single().id

        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수정됨")).planBoardId shouldBe boardId
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.FAILED))
            .planBoardId shouldBe boardId
    }

    "isManual: 시험일 없는 보드(직접 만든 일정)의 할일만 true — 보드가 섞이는 목록·보드 일간 조회" {
        val userId = seedUser("task-manual@test.com")
        val manualBoard = seedBoard(userId)                                         // 기본 플랜보드 (시험일 없음)
        val examBoard = seedBoard(userId, examDate = LocalDate.of(2026, 8, 1))     // AI 시험 계획 보드
        planTaskService.createTask(userId, taskRequest(planBoardId = manualBoard, taskName = "직접", startTime = "09:00", endTime = "10:00"))
        planTaskService.createTask(userId, taskRequest(planBoardId = examBoard, taskName = "시험", startTime = "19:00", endTime = "20:00"))
        val date = LocalDate.of(2026, 7, 15)
        val expected = listOf("직접" to true, "시험" to false)

        planTaskService.getDailyPlan(userId, date).tasks.map { it.taskName to it.isManual } shouldBe expected
        planTaskService.getWeeklyPlan(userId, date).days.single { it.planDate == "2026-07-15" }
            .tasks.map { it.taskName to it.isManual } shouldBe expected
        planTaskService.getBoardDailyPlan(userId, manualBoard, date).tasks.single().isManual shouldBe true
        planTaskService.getBoardDailyPlan(userId, examBoard, date).tasks.single().isManual shouldBe false
    }

    "isManual: 수정(상태 변경 포함) 단건 응답에도 보드 기준으로 들어간다" {
        val userId = seedUser("task-manual-update@test.com")
        val examBoard = seedBoard(userId, examDate = LocalDate.of(2026, 8, 1))
        planTaskService.createTask(userId, taskRequest(planBoardId = examBoard))
        val taskId = planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15)).tasks.single().id

        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수정됨")).isManual shouldBe false
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.FAILED))
            .isManual shouldBe false
    }

    // ---- 할일의 과목 귀속 (목록을 과목별로 분류하기 위한 제목) ----

    "createTask: 교과서를 지정하면 과목이 저장되고 목록·단건 응답에 subjectId/subjectName 이 실린다" {
        val userId = seedUser("task-subject@test.com")
        val boardId = seedBoard(userId)
        val subjectId = seedSubject("수학")
        val textbookId = seedTextbook(subjectId, title = "수학 교과서")

        planTaskService.createTask(userId, taskRequest(planBoardId = boardId, textbookId = textbookId))
        val date = LocalDate.of(2026, 7, 15)

        // 목록 3종(일간·보드 일간·주간) 모두 같은 과목이 실린다
        listOf(
            planTaskService.getDailyPlan(userId, date).tasks.single(),
            planTaskService.getBoardDailyPlan(userId, boardId, date).tasks.single(),
            planTaskService.getWeeklyPlan(userId, date).days.single { it.planDate == "2026-07-15" }.tasks.single()
        ).forEach { task ->
            task.subjectId shouldBe subjectId
            task.subjectName shouldBe "수학"
        }

        // 수정(상태 변경 포함) 단건 응답에도 같은 과목이 실린다
        val taskId = planTaskService.getDailyPlan(userId, date).tasks.single().id
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(taskName = "수학 2단원 복습")).subjectName shouldBe "수학"
        planTaskService.updateTask(userId, taskId, PlanTaskUpdateRequest(status = PlanTaskStatus.FAILED))
            .subjectId shouldBe subjectId
    }

    "createTask: 교과서를 안 보내면 과목이 붙지 않는다 (null)" {
        val userId = seedUser("task-nosubject@test.com")
        val boardId = seedBoard(userId)

        planTaskService.createTask(userId, taskRequest(planBoardId = boardId))

        val task = planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15)).tasks.single()
        task.subjectId shouldBe null
        task.subjectName shouldBe null
    }

    "createTask: 존재하지 않는 교과서면 400 INVALID_TEXTBOOK 이고 할일도 만들어지지 않는다" {
        val userId = seedUser("task-badtextbook@test.com")
        val boardId = seedBoard(userId)

        val e = shouldThrow<PlanTaskValidationException.InvalidTextbookException> {
            planTaskService.createTask(userId, taskRequest(planBoardId = boardId, textbookId = 999))
        }

        e.status.value shouldBe 400
        e.code shouldBe "INVALID_TEXTBOOK"
        planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 15)).tasks shouldBe emptyList()
    }

    "plan-generation: LLM 이 준 subject_name 이 요청 과목과 맞으면 저장되고, 못 맞추면 null 이다" {
        val userId = seedUser("gen-subject@test.com")
        val subjectId = seedSubject("수학")
        val math = seedTextbook(subjectId, title = "수학")
        val math1 = seedChapter(math, 1)
        val llm = FakePlanGenerationLlmClient(
            tasks = listOf(
                GeneratedPlanTask("수학 개념", 60, "수학"),      // 요청 과목명과 정확히 일치
                GeneratedPlanTask("수학 문제", 30, "  수학  "),  // 앞뒤 공백은 무시하고 일치
                GeneratedPlanTask("국어 문법", 30, "국어")       // 요청에 없는 과목 → null
            )
        )

        val response = PlanGenerationService(llm, noNiceSchoolDataFetcher()).generate(
            userId,
            PlanGenerationRequest(
                title = "과목 귀속",
                subjects = listOf(PlanGenerationSubjectInput(math, math1, math1)),
                startDate = "2026-07-04", // 토요일 — 06:30 부터 자리가 넉넉해 3개 다 배치된다
                daysRemaining = 1
            )
        )

        planTaskService.getDailyPlan(userId, LocalDate.of(2026, 7, 4)).tasks
            .map { it.taskName to it.subjectName } shouldBe listOf(
            "수학 개념" to "수학",
            "수학 문제" to "수학",
            "국어 문법" to null
        )
        // 계획 생성 응답에도 같은 과목이 실린다
        response.dailyPlans.single().tasks.map { Triple(it.taskName, it.subjectId, it.subjectName) } shouldBe listOf(
            Triple("수학 개념", subjectId, "수학"),
            Triple("수학 문제", subjectId, "수학"),
            Triple("국어 문법", null, null)
        )
    }
})

// context 의 "총 학습 기간: N일" 만큼 계획을 돌려준다. shortResponses 번째까지는 절반만 돌려준다.
private class FakePlanGenerationLlmClient(
    private var shortResponses: Int = 0,
    private val tasks: List<GeneratedPlanTask> = emptyList()
) : PlanGenerationLlmClient(dummyAppConfig()) {
    val contexts = java.util.Collections.synchronizedList(mutableListOf<String>())
    val studyStyleRules = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun generatePlan(context: String, studyStyleRules: String): GeneratedPlan {
        contexts += context
        this.studyStyleRules += studyStyleRules
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
    estimatedMinutes: Int = 120,
    textbookId: Int? = null
) = PlanTaskCreateRequest(planBoardId, planDate, taskName, startTime, endTime, estimatedMinutes, textbookId)

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
