import com.github.nepyh.rooter.common.APP_ZONE
import com.github.nepyh.rooter.module.notification.DeviceTokenService
import com.github.nepyh.rooter.module.notification.NotificationService
import com.github.nepyh.rooter.module.notification.NotificationSettingService
import com.github.nepyh.rooter.module.notification.TaskReminderJob
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.NotificationSettingsUpdateRequest
import com.github.nepyh.rooter.module.notification.model.DevicePlatform
import com.github.nepyh.rooter.module.notification.model.NotificationSettingTable
import com.github.nepyh.rooter.module.notification.model.NotificationTable
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokenTable
import com.github.nepyh.rooter.module.notification.push.PushMessage
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardRow
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.planboard.model.PlanTaskRow
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.planboard.model.SubjectTable
import com.github.nepyh.rooter.module.scheduler.JobRunRepo
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import java.time.temporal.ChronoUnit

/**
 * 할일 시작 전 알림 잡 통합 테스트 (로컬 PostgreSQL 필요).
 * 접속 정보는 환경변수로 오버라이드 가능: TEST_JDBC_URL / TEST_DB_USER / TEST_DB_PASSWORD
 */
class TaskReminderJobTest : StringSpec({

    val testDbUrl = System.getenv("TEST_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/planboard_test"
    val testDbUser = System.getenv("TEST_DB_USER") ?: "rooter_dev"
    val testDbPassword = System.getenv("TEST_DB_PASSWORD") ?: "wapapyrus"

    ensureTaskReminderTestDatabase(testDbUrl, testDbUser, testDbPassword)

    val db = Database.connect(
        url = testDbUrl,
        driver = "org.postgresql.Driver",
        user = testDbUser,
        password = testDbPassword
    )

    beforeSpec {
        transaction(db) {
            listOf(
                "job_runs", "notifications", "notification_settings", "user_device_tokens",
                "plan_tasks", "daily_plans", "plan_boards", "subjects", "users"
            ).forEach { exec("DROP TABLE IF EXISTS $it CASCADE") }
            SchemaUtils.create(
                UserTable, SubjectTable, PlanBoardTable, DailyPlanTable, PlanTaskTable,
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
        UserRow.new { email = "reminder${userSeq++}@test.com"; username = "t"; password = "x"; createdAt = OffsetDateTime.now() }.id.value
    }

    fun seedTask(
        userId: Int,
        start: String,
        date: LocalDate = day,
        name: String = "수학 2단원 복습",
        status: PlanTaskStatus = PlanTaskStatus.PENDING
    ): Int = transaction(db) {
        val board = PlanBoardRow.new {
            user = UserRow[userId]; title = "보드"; startDate = date; endDate = date; createdAt = OffsetDateTime.now()
        }
        val dailyPlan = DailyPlanRow.new { planBoard = board; planDate = date }
        val startTime = LocalTime.parse(start)
        PlanTaskRow.new {
            this.dailyPlan = dailyPlan; taskName = name
            this.startTime = startTime; endTime = startTime.plusMinutes(30); estimatedMinutes = 30
            this.status = status
        }.id.value
    }

    val settingService = NotificationSettingService()

    fun jobWith(sender: RecordingPushSender = RecordingPushSender()): Pair<TaskReminderJob, DeviceTokenService> {
        val deviceTokenService = DeviceTokenService(sender)
        return TaskReminderJob(NotificationService(deviceTokenService, settingService)) to deviceTokenService
    }

    fun TaskReminderJob.dueTaskIds(now: OffsetDateTime): List<String> = findDue(now).map { it.runKey }

    "기본 5분 전: 보낼 시각 전에는 없고, 5분 전부터 시작 2분 뒤 전까지만 후보다" {
        val userId = newUser()
        val taskId = seedTask(userId, "15:00")
        val (job, _) = jobWith()
        val runKey = "task_reminder_${taskId}_2026-11-02_15:00"

        job.dueTaskIds(at("14:54:59")).shouldBeEmpty()
        job.dueTaskIds(at("14:55")) shouldContainExactly listOf(runKey)
        job.dueTaskIds(at("15:01:59")) shouldContainExactly listOf(runKey)
        job.dueTaskIds(at("15:02")).shouldBeEmpty()
        job.findDue(at("14:55")).single().scheduledAt.isEqual(at("14:55")) shouldBe true
    }

    "설정한 분만큼 앞서 보내고, 사용자마다 자기 설정을 따른다" {
        val tenMinutes = newUser()
        val zeroMinutes = newUser()
        val defaultUser = newUser()
        settingService.update(tenMinutes, NotificationSettingsUpdateRequest(taskReminderLeadMinutes = 10))
        settingService.update(zeroMinutes, NotificationSettingsUpdateRequest(taskReminderLeadMinutes = 0))
        val ten = seedTask(tenMinutes, "15:00")
        val zero = seedTask(zeroMinutes, "15:00")
        val default = seedTask(defaultUser, "15:00")
        val (job, _) = jobWith()

        fun idsAt(time: String) = job.dueTaskIds(at(time)).map { it.split("_")[2].toInt() }

        idsAt("14:50") shouldContainExactly listOf(ten)
        idsAt("14:55") shouldContainExactlyInAnyOrder listOf(ten, default)
        idsAt("15:00") shouldContainExactlyInAnyOrder listOf(ten, zero, default)
    }

    "완료·실패 처리한 할일에는 보내지 않는다" {
        val userId = newUser()
        val pending = seedTask(userId, "15:00", status = PlanTaskStatus.PENDING)
        seedTask(userId, "15:00", status = PlanTaskStatus.DONE)
        seedTask(userId, "15:00", status = PlanTaskStatus.FAILED)
        val (job, _) = jobWith()

        job.dueTaskIds(at("14:56")) shouldContainExactly listOf("task_reminder_${pending}_2026-11-02_15:00")
    }

    "자정 직후 할일은 전날 밤에 보낸다" {
        val userId = newUser()
        val taskId = seedTask(userId, "00:03", date = day.plusDays(1))
        val (job, _) = jobWith()

        job.dueTaskIds(at("23:58")) shouldContainExactly listOf("task_reminder_${taskId}_2026-11-03_00:03")
    }

    "할일 시작 시각을 옮기면 runKey 가 바뀌어 새 시각으로 다시 보낸다" {
        val userId = newUser()
        val taskId = seedTask(userId, "15:00")
        val (job, _) = jobWith()
        val before = job.dueTaskIds(at("14:56")).single()

        transaction(db) { PlanTaskRow[taskId].startTime = LocalTime.of(16, 0) }

        val after = job.dueTaskIds(at("15:56")).single()
        after shouldNotBe before
        after shouldBe "task_reminder_${taskId}_2026-11-02_16:00"
    }

    "실행: 알림함에 남기고 할일 id 를 실은 푸시를 보낸다" {
        val userId = newUser()
        val taskId = seedTask(userId, "15:00", name = "영어 단어 암기")
        val sender = RecordingPushSender()
        val (job, devices) = jobWith(sender)
        devices.register(userId, DeviceTokenRegisterRequest("ExponentPushToken[phone]", DevicePlatform.IOS))

        job.execute(job.findDue(at("14:55")).single())

        val saved = transaction(db) { NotificationTable.selectAll().single() }
        saved[NotificationTable.userId].value shouldBe userId
        saved[NotificationTable.type] shouldBe NotificationType.TASK_REMINDER
        saved[NotificationTable.title] shouldBe TaskReminderJob.TITLE
        saved[NotificationTable.body] shouldBe "15:00에 '영어 단어 암기' 시작해요"
        val message: PushMessage = sender.sent.single().second
        message.data["planTaskId"] shouldBe taskId.toString()
        message.data["type"] shouldBe "TASK_REMINDER"
    }

    "실행: 할일 알림을 끈 사용자는 알림함에만 남고 푸시는 가지 않는다" {
        val userId = newUser()
        seedTask(userId, "15:00")
        settingService.update(userId, NotificationSettingsUpdateRequest(taskReminderEnabled = false))
        val sender = RecordingPushSender()
        val (job, devices) = jobWith(sender)
        devices.register(userId, DeviceTokenRegisterRequest("ExponentPushToken[phone]", DevicePlatform.IOS))

        job.execute(job.findDue(at("14:55")).single())

        transaction(db) { NotificationTable.selectAll().count() } shouldBe 1L
        sender.sent.shouldBeEmpty()
    }

    "엔진: 여러 틱이 돌아도 할일 하나에 알림은 한 번만 간다" {
        // 엔진은 실제 현재 시각으로 돈다 — 지금부터 3분 뒤 시작하는 할일을 만든다 (기본 5분 전이라 바로 후보)
        val userId = newUser()
        val startAt = ZonedDateTime.now(APP_ZONE).plusMinutes(3).truncatedTo(ChronoUnit.MINUTES)
        seedTask(userId, startAt.toLocalTime().toString(), date = startAt.toLocalDate())
        val sender = RecordingPushSender()
        val (job, devices) = jobWith(sender)
        devices.register(userId, DeviceTokenRegisterRequest("ExponentPushToken[phone]", DevicePlatform.IOS))
        val engine = SchedulerEngine(listOf(job), JobRunRepo())

        repeat(3) { engine.runTick() }

        sender.sent.size shouldBe 1
        transaction(db) { NotificationTable.selectAll().count() } shouldBe 1L
    }
})

private fun ensureTaskReminderTestDatabase(url: String, user: String, password: String) {
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
