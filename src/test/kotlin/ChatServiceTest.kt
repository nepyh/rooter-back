import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.chat.ChatLlmClient
import com.github.nepyh.rooter.module.chat.ChatService
import com.github.nepyh.rooter.module.chat.PLAN_NOT_APPLIED_REPLY
import com.github.nepyh.rooter.module.chat.POSTPONE_NOTHING_REPLY
import com.github.nepyh.rooter.module.chat.dto.AiChatPlanUpdate
import com.github.nepyh.rooter.module.chat.dto.AiChatPostpone
import com.github.nepyh.rooter.module.chat.dto.AiChatResult
import com.github.nepyh.rooter.module.chat.dto.AiChatTask
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.chat.planBoardSummaryOf
import com.github.nepyh.rooter.module.chat.postponeBlockedReply
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
        postponedFrom: LocalDate? = null
    ): Int = transaction(db) {
        PlanTaskRow.new {
            postponedFromDate = postponedFrom
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
        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "10분씩 줄여줘")

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

        // 새 일정(busy window) 없이 소요시간만 줄였으므로 겹치는 게 없다 → 남은 태스크는 원래 시각에서 길이만 바뀐다
        response.planChanged shouldBe true
        response.updatedTasks!!.map { Triple(it.taskName, it.startTime, it.isCompleted) } shouldBe listOf(
            Triple("완료한 태스크", "17:00", true),
            Triple("남은 태스크1", "18:10", false),
            Triple("남은 태스크2", "19:10", false)
        )
        response.updatedTasks!!.map { it.endTime } shouldBe listOf("18:00", "18:50", "19:50")
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
        response.updatedTasks!!.single().startTime shouldBe "18:00"
    }

    "재조정해도 새 일정과 안 겹치는 태스크는 id·시각이 그대로고, 겹치는 태스크만 원래 시각 이후로 옮긴다" {
        val (userId, dailyPlanId) = seedDailyPlan()
        val a = seedTask(dailyPlanId, "개념 정리", LocalTime.of(16, 40), LocalTime.of(17, 20), completed = false)
        val b = seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 0), LocalTime.of(18, 30), completed = false)
        val llm = FakeChatLlmClient(
            AiChatResult(
                reply_message = "학원 시간을 피해서 옮겼어요",
                plan_changed = true,
                plan_update = AiChatPlanUpdate(
                    tasks = listOf(AiChatTask("개념 정리", 40), AiChatTask("문제 풀이", 30)),
                    busy_window_start = "17:30", busy_window_end = "19:00"
                )
            )
        )

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "5시 반부터 7시까지 학원")

        response.updatedTasks!!.map { Triple(it.id, it.taskName, "${it.startTime}~${it.endTime}") } shouldBe listOf(
            Triple(a, "개념 정리", "16:40~17:20"),   // 안 겹침 → 그대로 (id 유지)
            Triple(b, "문제 풀이", "19:00~19:30")    // 겹침 → 학원 끝난 뒤로 (id 유지)
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
        // 계획 날짜(2026-09-29) 한국 시간 18:05 — 지금 이후인 18:10 부터
        val todayClock = Clock.fixed(Instant.parse("2026-09-29T09:05:00Z"), ZoneOffset.UTC)

        val response = ChatService(llm, schoolDataFetcher, todayClock).sendMessage(userId, dailyPlanId, "4시 반부터 5시 반까지 학원이었어")

        response.updatedTasks!!.single().startTime shouldBe "18:10"
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

    fun postponeLlm(names: List<String>, toDate: String? = null, reply: String = "미룰게요") = FakeChatLlmClient(
        AiChatResult(reply_message = reply, plan_changed = true, postpone = AiChatPostpone(task_names = names, to_date = toDate))
    )

    "미루기: 오늘 할일을 다음 날로 옮기고, 그날 기존 할일을 쉬는 시간 두고 피해서 배치하고, 원래 날짜를 남긴다" {
        val wednesday = tuesday.plusDays(1)
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val conceptId = seedTask(dailyPlanId, "개념 정리", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false)
        val problemId = seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)
        // 수요일(등교일, 학교 정보 없음 → 16:30 하교)에 이미 있는 할일 16:30~17:00
        val wednesdayPlanId = transaction(db) {
            DailyPlanRow.new { planBoard = DailyPlanRow[dailyPlanId].planBoard; planDate = wednesday }.id.value
        }
        seedTask(wednesdayPlanId, "영어 단어", LocalTime.of(16, 30), LocalTime.of(17, 0), completed = false)
        val llm = postponeLlm(listOf("개념 정리", "문제 풀이"))

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "오늘 거 내일로 미뤄줘")

        response.planChanged shouldBe true
        response.updatedTasks shouldBe emptyList()
        response.movedTasks!!.map { "${it.taskName} ${it.startTime}~${it.endTime}" } shouldBe listOf(
            "개념 정리 17:10~18:10", "문제 풀이 18:20~19:10"
        )
        response.movedTasks!!.map { it.dailyPlanId }.toSet() shouldBe setOf(wednesdayPlanId)
        response.reply shouldBe "'개념 정리', '문제 풀이' 할일을 9월 30일(수) 17:10~18:10, 18:20~19:10로 미뤘어요."
        transaction(db) {
            listOf(conceptId, problemId).map { PlanTaskRow[it].postponedFromDate } shouldBe listOf(tuesday, tuesday)
        }
        llm.lastToday shouldBe "2026-09-01 (화요일)"
    }

    "미루기: 어제에서 미뤄져 온 할일은 이틀 연속 미루지 못하고, 다른 할일만 옮긴다" {
        val (userId, dailyPlanId) = seedDailyPlan(boardEnd = tuesday.plusDays(7))
        val postponedId = seedTask(dailyPlanId, "개념 정리", LocalTime.of(17, 0), LocalTime.of(18, 0), completed = false, postponedFrom = tuesday.minusDays(1))
        seedTask(dailyPlanId, "문제 풀이", LocalTime.of(18, 10), LocalTime.of(19, 0), completed = false)
        val llm = postponeLlm(listOf("개념 정리", "문제 풀이"))

        val response = ChatService(llm, schoolDataFetcher, otherDayClock).sendMessage(userId, dailyPlanId, "다 내일로 미뤄줘")

        response.movedTasks!!.map { it.taskName } shouldBe listOf("문제 풀이")
        response.updatedTasks!!.map { it.taskName } shouldBe listOf("개념 정리")
        response.reply shouldBe "'문제 풀이' 할일을 9월 30일(수) 16:30~17:20로 미뤘어요. '개념 정리'은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요."
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

        response.reply shouldBe "'수학 개념' 할일을 9월 30일(수) 16:30~17:30로 미뤘어요. '영어 단어'은(는) 어제 미룬 할일이라 이틀 연속으로는 미룰 수 없어요."
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

    override suspend fun adjustPlan(
        grade: Int,
        studyStyleSummary: String,
        targetDate: String,
        today: String,
        currentTasksJson: String,
        chatHistoryJson: String,
        planBoardSummary: String,
        userMessage: String
    ): AiChatResult? {
        lastToday = today
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
