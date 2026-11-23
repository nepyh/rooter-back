import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackRow
import com.github.nepyh.rooter.module.feedback.model.DailyFeedbackTable
import com.github.nepyh.rooter.module.notification.DailyFeedbackReminderJob
import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.NotificationSettingService
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.model.DevicePlatform
import com.github.nepyh.rooter.module.notification.model.NotificationSettingTable
import com.github.nepyh.rooter.module.notification.model.NotificationTable
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokenTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * 저녁 오늘 공부 설문 알림 잡 통합 테스트 (#374, 로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class DailyFeedbackReminderJobTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureDailyFeedbackReminderTestDatabase(testDbUrl, testDbUser, testDbPassword)

    val db = Database.connect(url = testDbUrl, driver = "org.postgresql.Driver", user = testDbUser, password = testDbPassword)

    beforeSpec {
        transaction(db) {
            listOf(
                "job_runs", "notifications", "notification_settings", "user_device_tokens", "daily_feedback",
                "plan_tasks", "daily_plans", "plan_boards", "subjects", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, SubjectTable, PlanBoardTable, DailyPlanTable, PlanTaskTable, DailyFeedbackTable,
                UserDeviceTokenTable, NotificationSettingTable, NotificationTable, JobRunTable
            )
        }
    }

    beforeEach {
        transaction(db) {
            JobRunTable.deleteAll()
            NotificationTable.deleteAll()
            NotificationSettingTable.deleteAll()
            UserDeviceTokenTable.deleteAll()
            DailyFeedbackTable.deleteAll()
            PlanTaskTable.deleteAll()
            DailyPlanTable.deleteAll()
            PlanBoardTable.deleteAll()
            UserTable.deleteAll()
        }
    }

    val day = LocalDate.of(2026, 11, 2)
    fun at(time: String, date: LocalDate = day): OffsetDateTime =
        ZonedDateTime.of(date, LocalTime.parse(time), APP_ZONE).toOffsetDateTime()

    var userSeq = 0
    fun newUser(): Int = transaction(db) {
        UserRow.new { email = "daily-reminder${userSeq++}@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }.id.value
    }

    /** 보드 하나와 그날 계획을 만들고 할일을 [taskCount] 개 단다. daily_plan id 를 돌려준다 */
    fun seedPlan(
        userId: Int,
        date: LocalDate = day,
        taskCount: Int = 1,
        representative: Boolean = false,
        archived: Boolean = false
    ): Int = transaction(db) {
        val board = PlanBoardRow.new {
            user = UserRow[userId]; title = "보드"; startDate = date; endDate = date; createdAt = OffsetDateTime.now()
            isRepresentative = representative; isArchived = archived
        }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = date }
        repeat(taskCount) { i ->
            PlanTaskRow.new {
                this.dailyPlan = dailyPlan; taskName = "할일 $i"
                startTime = LocalTime.of(15, 0); endTime = LocalTime.of(15, 30); estimatedMinutes = 30
            }
        }
        dailyPlan.id.value
    }

    fun submitSurvey(dailyPlanId: Int, planTaskId: Int? = null) = transaction(db) {
        DailyFeedbackRow.new {
            dailyPlan = DailyPlanRow[dailyPlanId]
            this.planTaskId = planTaskId?.let { EntityID(it, PlanTaskTable) }
            difficulty = "적당"
        }
    }

    val settingService = NotificationSettingService()

    fun jobWith(sender: RecordingPushSender = RecordingPushSender()): Pair<DailyFeedbackReminderJob, DeviceTokenService> {
        val deviceTokenService = DeviceTokenService(sender)
        return DailyFeedbackReminderJob(NotificationService(deviceTokenService, settingService)) to deviceTokenService
    }

    fun DailyFeedbackReminderJob.runKeys(now: OffsetDateTime): List<String> = findDue(now).map { it.runKey }

    "매일 22:00 에 한 번, 22:00 부터 5분 안에서만 후보다" {
        val userId = newUser()
        seedPlan(userId)
        val (job, _) = jobWith()

        DailyFeedbackReminderJob.SEND_AT.toString() shouldBe "22:00"
        listOf("19:00", "19:30", "21:00", "21:59:59").forEach { job.runKeys(at(it)).shouldBeEmpty() }
        job.runKeys(at("22:00")) shouldContainExactly listOf("daily_feedback_reminder_${userId}_2026-11-02")
        job.runKeys(at("22:04:59")) shouldContainExactly listOf("daily_feedback_reminder_${userId}_2026-11-02")
        job.runKeys(at("22:05")).shouldBeEmpty()
        job.runKeys(at("23:30")).shouldBeEmpty()
        job.findDue(at("22:02")).single().scheduledAt.isEqual(at("22:00")) shouldBe true
    }

    "runKey 는 학생·날짜라 하루 한 번만 가고, 다음 날은 다른 runKey 다" {
        val userId = newUser()
        seedPlan(userId)
        seedPlan(userId, date = day.plusDays(1))
        val (job, _) = jobWith()

        job.runKeys(at("22:00")) shouldBe job.runKeys(at("22:03"))
        job.runKeys(at("22:00", day.plusDays(1))) shouldContainExactly listOf("daily_feedback_reminder_${userId}_2026-11-03")
    }

    "22시 전에 오늘 일일 설문을 냈으면 빠진다 — 할일 설문(plan_task_id 있음)은 일일 설문이 아니다" {
        val submitted = newUser()
        val taskSurveyOnly = newUser()
        val submittedPlan = seedPlan(submitted)
        val taskPlan = seedPlan(taskSurveyOnly)
        val (job, _) = jobWith()

        submitSurvey(submittedPlan)
        val taskId = transaction(db) { PlanTaskRow.find { PlanTaskTable.dailyPlanId eq taskPlan }.first().id.value }
        submitSurvey(taskPlan, planTaskId = taskId)

        job.runKeys(at("22:00")) shouldContainExactly listOf("daily_feedback_reminder_${taskSurveyOnly}_2026-11-02")
    }

    "오늘 계획이 없거나, 할일이 없거나, 보관한 보드뿐이면 보내지 않는다" {
        val otherDay = newUser()
        val noTasks = newUser()
        val archived = newUser()
        val target = newUser()
        seedPlan(otherDay, date = day.minusDays(1))
        seedPlan(noTasks, taskCount = 0)
        seedPlan(archived, archived = true)
        seedPlan(target)
        val (job, _) = jobWith()

        job.runKeys(at("22:00")) shouldContainExactly listOf("daily_feedback_reminder_${target}_2026-11-02")
    }

    "보드가 여러 개여도 학생당 한 번, 대표 보드의 계획을 싣는다" {
        val userId = newUser()
        seedPlan(userId)
        val representativePlan = seedPlan(userId, representative = true)
        seedPlan(userId)
        val (job, _) = jobWith()

        val due = job.findDue(at("22:00"))
        due.size shouldBe 1
        due.single().payload shouldBe """{"userId":$userId,"dailyPlanId":$representativePlan}"""
    }

    "대표 보드가 없으면 id 가 가장 작은 계획을 싣고, 어느 계획이든 일일 설문을 냈으면 빠진다" {
        val userId = newUser()
        val first = seedPlan(userId)
        val second = seedPlan(userId)
        val (job, _) = jobWith()

        job.findDue(at("22:00")).single().payload shouldBe """{"userId":$userId,"dailyPlanId":$first}"""

        submitSurvey(second)
        job.runKeys(at("22:00")).shouldBeEmpty()
    }

    "여러 학생에게 각각 보낸다" {
        val a = newUser()
        val b = newUser()
        seedPlan(a)
        seedPlan(b)
        val (job, _) = jobWith()

        job.runKeys(at("22:00")) shouldContainExactlyInAnyOrder listOf(
            "daily_feedback_reminder_${a}_2026-11-02",
            "daily_feedback_reminder_${b}_2026-11-02"
        )
    }

    "실행: 알림함에 남기고 dailyPlanId 를 실은 푸시를 보낸다" {
        val userId = newUser()
        val planId = seedPlan(userId)
        val sender = RecordingPushSender()
        val (job, devices) = jobWith(sender)
        devices.register(userId, DeviceTokenRegisterRequest("ExponentPushToken[phone]", DevicePlatform.IOS))

        job.execute(job.findDue(at("22:00")).single())

        val saved = transaction(db) { NotificationTable.selectAll().single() }
        saved[NotificationTable.userId].value shouldBe userId
        saved[NotificationTable.type] shouldBe NotificationType.DAILY_FEEDBACK_REMINDER
        saved[NotificationTable.title] shouldBe "오늘 공부 어떠셨나요?"
        saved[NotificationTable.body] shouldBe "루티에게 오늘 공부를 알려주세요!"
        val message = sender.sent.single().second
        message.data["dailyPlanId"] shouldBe planId.toString()
        message.data["type"] shouldBe "DAILY_FEEDBACK_REMINDER"
    }

    "실행: 할일 알림을 끈 사용자는 알림함에만 남고 푸시는 가지 않는다" {
        val userId = newUser()
        seedPlan(userId)
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderEnabled = false))
        val sender = RecordingPushSender()
        val (job, devices) = jobWith(sender)
        devices.register(userId, DeviceTokenRegisterRequest("ExponentPushToken[phone]", DevicePlatform.IOS))

        job.execute(job.findDue(at("22:00")).single())

        transaction(db) { NotificationTable.selectAll().count() } shouldBe 1L
        sender.sent.shouldBeEmpty()
    }
})

private fun ensureDailyFeedbackReminderTestDatabase(url: String, user: String, password: String) {
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
