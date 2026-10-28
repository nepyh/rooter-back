import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.JobRunRepo
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.scheduler.model.JobStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SchedulerEngineTest : StringSpec({

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
            exec("DROP TABLE IF EXISTS job_runs CASCADE")
            SchemaUtils.create(JobRunTable)
        }
    }

    beforeEach {
        transaction(db) { JobRunTable.deleteAll() }
    }

    /** runKey 목록을 매 틱 그대로 내는 잡. 실행된 runKey 를 [executed] 에 쌓는다 */
    fun jobOf(jobType: String, executed: MutableList<String>, vararg runKeys: String) = object : SchedulerJob {
        override val jobType = jobType
        override fun findDue(now: OffsetDateTime): List<DueJob> = runKeys.map { DueJob(it, now) }
        override suspend fun execute(due: DueJob) {
            executed += due.runKey
        }
    }

    "findDue 가 이미 기록된 runKey 를 계속 내도 실행은 하지 않고, 새 후보만 한 번 실행한다" {
        val executed = mutableListOf<String>()
        val now = OffsetDateTime.now()
        val repo = JobRunRepo()
        val job = jobOf("test_job", executed, "done_key", "new_key")
        // 앞선 틱에서 이미 처리해 job_runs 에 남아 있는 작업
        repo.claim("test_job", DueJob("done_key", now))

        SchedulerEngine(listOf(job), repo).runTick()
        executed shouldBe listOf("new_key")

        // 파생 결과는 사라지지 않으므로 다음 틱에도 두 후보가 다시 나온다 — 그래도 실행도 claim 도 없다
        SchedulerEngine(listOf(job), repo).runTick()
        executed shouldBe listOf("new_key")
        transaction(db) { JobRunTable.selectAll().count() } shouldBe 2L
    }

    "실행에 실패해 FAILED 로 남은 작업도 다시 실행하지 않는다" {
        val executed = mutableListOf<String>()
        val now = OffsetDateTime.now()
        val repo = JobRunRepo()
        repo.claim("ci_job", DueJob("boom_key", now))
        repo.markFailed("ci_job", "boom_key", IllegalStateException("AI 생성 실패"))

        SchedulerEngine(listOf(jobOf("ci_job", executed, "boom_key")), repo).runTick()

        executed shouldBe emptyList()
        transaction(db) { JobRunTable.selectAll().single()[JobRunTable.status] } shouldBe JobStatus.FAILED.value
    }

    "한 틱에 같은 runKey 가 두 번 파생돼도 한 번만 실행한다 (claim 유니크 키)" {
        val executed = mutableListOf<String>()

        SchedulerEngine(listOf(jobOf("dup_job", executed, "same_key", "same_key")), JobRunRepo()).runTick()

        executed shouldBe listOf("same_key")
        transaction(db) { JobRunTable.selectAll().count() } shouldBe 1L
    }
})

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
