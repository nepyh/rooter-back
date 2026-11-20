import com.github.nepyh.rooter.module.scheduler.DueJob
import com.github.nepyh.rooter.module.scheduler.JobMonitor
import com.github.nepyh.rooter.module.scheduler.JobRunRepo
import com.github.nepyh.rooter.module.scheduler.SchedulerEngine
import com.github.nepyh.rooter.module.scheduler.SchedulerJob
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.scheduler.model.JobStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.eq

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

    "재시도 간격(retryDelays)을 두지 않은 잡은 실패해 FAILED 로 남은 작업을 다시 실행하지 않는다" {
        val executed = mutableListOf<String>()
        val now = OffsetDateTime.now()
        val repo = JobRunRepo()
        repo.claim("ci_job", DueJob("boom_key", now))
        repo.markFailed("ci_job", "boom_key", IllegalStateException("AI 생성 실패"))

        SchedulerEngine(listOf(jobOf("ci_job", executed, "boom_key")), repo).runTick()

        executed shouldBe emptyList()
        transaction(db) { JobRunTable.selectAll().single()[JobRunTable.status] } shouldBe JobStatus.FAILED.value
    }

    /** retryDelays 를 둔 잡. [fail] 이 true 인 동안 실행이 실패한다 */
    fun retryingJob(executed: MutableList<String>, delays: List<Duration>, fail: () -> Boolean = { false }, vararg runKeys: String) =
        object : SchedulerJob {
            override val jobType = "retry_job"
            override val retryDelays = delays
            override fun findDue(now: OffsetDateTime): List<DueJob> = runKeys.map { DueJob(it, now) }
            override suspend fun execute(due: DueJob) {
                executed += due.runKey
                if (fail()) error("LLM 실패")
            }
        }

    /** 실패 행의 finished_at 을 [minutesAgo] 분 전으로 돌린다 (재시도 간격이 지났는지 시험하려고) */
    fun failedMinutesAgo(runKey: String, minutesAgo: Long) = transaction(db) {
        JobRunTable.update({ JobRunTable.runKey eq runKey }) {
            it[JobRunTable.finishedAt] = OffsetDateTime.now().minusMinutes(minutesAgo)
        }
    }

    fun rowOf(runKey: String) = transaction(db) {
        JobRunTable.selectAll().where { JobRunTable.runKey eq runKey }.single()
            .let { it[JobRunTable.status] to it[JobRunTable.retryCount] }
    }

    "재시도 잡: 실패한 작업은 간격이 지나면 다시 실행하고, 성공하면 done·retry_count 1 이 된다 (#318)" {
        val executed = mutableListOf<String>()
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val engine = SchedulerEngine(listOf(retryingJob(executed, listOf(Duration.ofMinutes(5)), { failing.get() }, "r")), JobRunRepo())

        engine.runTick()
        rowOf("r") shouldBe (JobStatus.FAILED.value to 0)

        // 간격(5분)이 지나기 전에는 다시 실행하지 않는다
        failedMinutesAgo("r", 4)
        engine.runTick()
        executed shouldBe listOf("r")

        failing.set(false)
        failedMinutesAgo("r", 6)
        engine.runTick()
        executed shouldBe listOf("r", "r")
        rowOf("r") shouldBe (JobStatus.DONE.value to 1)

        // 끝난 작업은 다시 실행하지 않는다
        engine.runTick()
        executed shouldBe listOf("r", "r")
    }

    "재시도 잡: 간격 목록 크기만큼만 재시도하고, n 번째 재시도는 n 번째 간격을 기다린다 (#318)" {
        val executed = mutableListOf<String>()
        val engine = SchedulerEngine(
            listOf(retryingJob(executed, listOf(Duration.ofMinutes(5), Duration.ofMinutes(15)), { true }, "x")),
            JobRunRepo()
        )

        engine.runTick() // 처음 실행 실패
        failedMinutesAgo("x", 6)
        engine.runTick() // 1번째 재시도 실패
        rowOf("x") shouldBe (JobStatus.FAILED.value to 1)

        failedMinutesAgo("x", 6) // 2번째 재시도는 15분을 기다린다
        engine.runTick()
        executed.size shouldBe 2

        failedMinutesAgo("x", 16)
        engine.runTick() // 2번째 재시도 실패 → 소진
        rowOf("x") shouldBe (JobStatus.FAILED.value to 2)

        failedMinutesAgo("x", 600)
        engine.runTick()
        executed.size shouldBe 3
    }

    "재시도 claim 은 한 곳만 가져간다 — 배포 중 옛·새 서버가 같은 실패 작업을 동시에 재시도하지 않는다 (#318)" {
        val repo = JobRunRepo()
        repo.claim("race_job", DueJob("k", OffsetDateTime.now()))
        repo.markFailed("race_job", "k", IllegalStateException("실패"))

        repo.claimRetry("race_job", "k", retryCount = 0) shouldBe true
        repo.claimRetry("race_job", "k", retryCount = 0) shouldBe false
        transaction(db) { JobRunTable.selectAll().single()[JobRunTable.status] } shouldBe JobStatus.PENDING.value
    }

    "재시도 대상은 findDue 가 아직 내는 작업뿐이다 — 후보에서 빠진 실패 작업은 재시도하지 않는다 (#318)" {
        val repo = JobRunRepo()
        repo.claim("retry_job", DueJob("gone", OffsetDateTime.now()))
        repo.markFailed("retry_job", "gone", IllegalStateException("실패"))
        failedMinutesAgo("gone", 600)
        val executed = mutableListOf<String>()

        SchedulerEngine(listOf(retryingJob(executed, listOf(Duration.ofMinutes(5)), runKeys = arrayOf("other"))), repo).runTick()

        executed shouldBe listOf("other")
        rowOf("gone") shouldBe (JobStatus.FAILED.value to 0)
    }

    "한 틱에 같은 runKey 가 두 번 파생돼도 한 번만 실행한다 (claim 유니크 키)" {
        val executed = mutableListOf<String>()

        SchedulerEngine(listOf(jobOf("dup_job", executed, "same_key", "same_key")), JobRunRepo()).runTick()

        executed shouldBe listOf("same_key")
        transaction(db) { JobRunTable.selectAll().count() } shouldBe 1L
    }

    "잡마다 따로 돈다: 한 잡의 실행이 끝나지 않아도 다른 잡은 매 틱 실행된다" {
        val stuck = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val slowJob = object : SchedulerJob {
            override val jobType = "slow_job"
            override fun findDue(now: OffsetDateTime) = listOf(DueJob("slow_key", now))
            override suspend fun execute(due: DueJob) {
                slowStarted.complete(Unit)
                stuck.await() // LLM 호출처럼 오래 걸리는 실행
            }
        }
        val fastRuns = AtomicInteger()
        val tick = AtomicInteger()
        val fastJob = object : SchedulerJob {
            override val jobType = "fast_job"
            override fun findDue(now: OffsetDateTime) = listOf(DueJob("fast_${tick.getAndIncrement()}", now))
            override suspend fun execute(due: DueJob) {
                fastRuns.incrementAndGet()
            }
        }
        val scope = CoroutineScope(SupervisorJob())

        try {
            // 느린 잡을 먼저 등록한다 — 예전처럼 한 루프에서 차례로 돌았다면 빠른 잡은 한 번도 돌지 못한다
            SchedulerEngine(listOf(slowJob, fastJob), JobRunRepo(), Duration.ofMillis(50)).start(scope)
            withTimeout(5_000) {
                slowStarted.await()
                while (fastRuns.get() < 3) delay(20)
            }
        } finally {
            stuck.complete(Unit)
            scope.cancel()
        }
    }

    /** 보낸 heartbeat 를 "ok:간격" 으로 쌓는 가짜 모니터 */
    class RecordingMonitor : JobMonitor {
        val beats = CopyOnWriteArrayList<String>()
        override fun heartbeat(ok: Boolean, intervalMinutes: Int) {
            beats += "$ok:$intervalMinutes"
        }
    }

    /** 틱마다 새 runKey 를 내고, [fail] 이 true 를 돌려주는 동안 실행이 실패하는 잡 */
    fun monitoredJob(jobType: String, monitored: Boolean = true, fail: () -> Boolean = { false }) = object : SchedulerJob {
        private val tick = AtomicInteger()
        override val jobType = jobType
        override val monitored = monitored
        override fun findDue(now: OffsetDateTime) = listOf(DueJob("${jobType}_${tick.getAndIncrement()}", now))
        override suspend fun execute(due: DueJob) {
            if (fail()) error("실행 실패")
        }
    }

    /** 엔진을 돌리며 [until] 이 될 때까지 기다린 뒤 멈춘다 */
    suspend fun runEngine(engine: SchedulerEngine, until: suspend () -> Boolean) {
        val scope = CoroutineScope(SupervisorJob())
        try {
            engine.start(scope)
            withTimeout(5_000) { while (!until()) delay(20) }
        } finally {
            scope.cancel()
        }
    }

    "runJob 은 파생·실행이 모두 성공하면 true, 파생이나 실행이 하나라도 실패하면 false 를 돌려준다" {
        val engine = SchedulerEngine(emptyList(), JobRunRepo())
        engine.runJob(jobOf("ok_job", mutableListOf(), "a", "b")) shouldBe true
        engine.runJob(object : SchedulerJob {
            override val jobType = "derive_fail"
            override fun findDue(now: OffsetDateTime): List<DueJob> = error("파생 실패")
            override suspend fun execute(due: DueJob) = Unit
        }) shouldBe false
        engine.runJob(object : SchedulerJob {
            override val jobType = "exec_fail"
            override fun findDue(now: OffsetDateTime) = listOf(DueJob("x", now), DueJob("y", now))
            override suspend fun execute(due: DueJob) {
                if (due.runKey == "x") error("실행 실패")
            }
        }) shouldBe false
    }

    "heartbeat 는 잡마다가 아니라 스케줄러 전체로 하나 — 모니터 대상 잡이 모두 정상이면 OK, 마지막 틱이 실패하면 ERROR, 다시 성공하면 OK" {
        val monitor = RecordingMonitor()
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        // 모니터 대상이 아닌 잡은 매번 실패해도 heartbeat 에 영향이 없다
        val jobs = listOf(monitoredJob("a_job"), monitoredJob("b_job") { failing.get() }, monitoredJob("example_job", monitored = false) { true })

        runEngine(SchedulerEngine(jobs, JobRunRepo(), Duration.ofMillis(50), monitor)) {
            when {
                monitor.beats.size >= 3 && !failing.get() && "false:1" !in monitor.beats -> failing.set(true)
                "false:1" in monitor.beats && failing.get() -> failing.set(false)
            }
            "false:1" in monitor.beats && monitor.beats.last() == "true:1"
        }

        monitor.beats.first() shouldBe "true:1"
        monitor.beats.all { it == "true:1" || it == "false:1" } shouldBe true
    }

    "잡 루프가 멈추면 heartbeat 는 계속 ERROR 로 간다 (#317)" {
        val monitor = RecordingMonitor()
        val dying = object : SchedulerJob {
            override val jobType = "dying_job"
            override val monitored = true
            override fun findDue(now: OffsetDateTime) = listOf(DueJob("d", now))
            // runJob 이 잡지 않는 Error 로 루프를 끝낸다 (운영에선 DB 오류가 claim 에서 터지는 경우)
            override suspend fun execute(due: DueJob) = throw AssertionError("루프를 끝내는 오류")
        }

        runEngine(SchedulerEngine(listOf(dying, monitoredJob("alive_job")), JobRunRepo(), Duration.ofMillis(50), monitor)) {
            monitor.beats.count { it == "false:1" } >= 3
        }

        monitor.beats.takeLast(3) shouldBe listOf("false:1", "false:1", "false:1")
    }

    "한 틱이 stallAfter 넘게 끝나지 않으면 heartbeat 가 ERROR, 모니터 대상 잡이 없으면 heartbeat 를 보내지 않는다" {
        val monitor = RecordingMonitor()
        val stuck = CompletableDeferred<Unit>()
        val stalling = object : SchedulerJob {
            override val jobType = "stalling_job"
            override val monitored = true
            override fun findDue(now: OffsetDateTime) = listOf(DueJob("s", now))
            override suspend fun execute(due: DueJob) = stuck.await() // 끝나지 않는 LLM 호출
        }

        try {
            runEngine(SchedulerEngine(listOf(stalling), JobRunRepo(), Duration.ofMillis(50), monitor, stallAfter = Duration.ofMillis(200))) {
                "false:1" in monitor.beats
            }
        } finally {
            stuck.complete(Unit)
        }
        monitor.beats.first() shouldBe "true:1"

        val silent = RecordingMonitor()
        val ran = AtomicInteger()
        val plain = object : SchedulerJob {
            override val jobType = "plain_job"
            override fun findDue(now: OffsetDateTime) = listOf(DueJob("p_${ran.getAndIncrement()}", now))
            override suspend fun execute(due: DueJob) = Unit
        }
        runEngine(SchedulerEngine(listOf(plain), JobRunRepo(), Duration.ofMillis(50), silent)) { ran.get() >= 3 }
        silent.beats shouldBe emptyList()
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
