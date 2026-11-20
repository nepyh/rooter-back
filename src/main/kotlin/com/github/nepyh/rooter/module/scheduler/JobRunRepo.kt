package com.github.nepyh.rooter.module.scheduler

import com.github.nepyh.rooter.module.scheduler.model.JobRunRow
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.scheduler.model.JobStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 이번 틱에 claim 할 작업. [retryCount] 가 null 이면 처음 실행([JobRunRepo.claim]),
 * 아니면 실패한 작업의 재시도이고 값은 지금 job_runs 의 retry_count 다([JobRunRepo.claimRetry]).
 */
data class Claimable(val due: DueJob, val retryCount: Int?)

class JobRunRepo {

    /**
     * 후보 중 이번 틱에 claim 할 것을 고른다. 파생 결과는 매 틱 다시 나오므로, 이 걸러내기가 없으면 끝난 작업도
     * 매번 claim(INSERT) 까지 간다. 중복 실행을 실제로 막는 것은 여전히 [claim]·[claimRetry] 의 조건이고,
     * 이 조회는 헛 INSERT·UPDATE 를 줄이는 용도다.
     *
     * - job_runs 에 기록이 없으면 새 작업 ([Claimable.retryCount] = null)
     * - 실패(failed)했고 재시도가 남았고(retry_count < [retryDelays] 크기) 직전 실패에서 `retryDelays[retry_count]` 가
     *   지났으면 재시도 ([Claimable.retryCount] = 지금의 retry_count) (#318)
     * - 그 밖(실행 중·완료·재시도 소진·아직 대기 중)은 뺀다
     */
    fun findClaimable(
        jobType: String,
        dueJobs: List<DueJob>,
        retryDelays: List<Duration> = emptyList(),
        now: OffsetDateTime = OffsetDateTime.now()
    ): List<Claimable> {
        if (dueJobs.isEmpty()) return emptyList()

        val recorded = transaction {
            JobRunTable.select(JobRunTable.runKey, JobRunTable.status, JobRunTable.retryCount, JobRunTable.finishedAt)
                .where { (JobRunTable.jobType eq jobType) and (JobRunTable.runKey inList dueJobs.map { it.runKey }) }
                .associate { it[JobRunTable.runKey] to it }
        }
        return dueJobs.distinctBy { it.runKey }.mapNotNull { due ->
            val row = recorded[due.runKey] ?: return@mapNotNull Claimable(due, retryCount = null)
            if (row[JobRunTable.status] != JobStatus.FAILED.value) return@mapNotNull null

            val retryCount = row[JobRunTable.retryCount]
            val delay = retryDelays.getOrNull(retryCount) ?: return@mapNotNull null // 재시도 소진(또는 재시도 안 하는 잡)
            // finished_at 이 없는 실패 행은 없지만, 있더라도 멈춰 있지 않게 바로 재시도 대상으로 본다
            val failedAt = row[JobRunTable.finishedAt] ?: return@mapNotNull Claimable(due, retryCount)
            if (failedAt.plus(delay).isAfter(now)) return@mapNotNull null

            Claimable(due, retryCount)
        }
    }

    /**
     * job_runs 에 작업 지시를 기록하며 claim 한다.
     * (job_type, run_key) 가 이미 존재하면 (실행 로그가 남아 있으면) false 를 반환해 중복 실행을 막는다.
     * insertIgnore (ON CONFLICT DO NOTHING) 는 DAO(Row) 로 표현할 수 없어 Table 을 직접 사용한다.
     */
    fun claim(jobType: String, due: DueJob): Boolean {
        val inserted = transaction {
            JobRunTable.insertIgnore {
                it[JobRunTable.jobType] = jobType
                it[JobRunTable.runKey] = due.runKey
                it[JobRunTable.status] = JobStatus.PENDING.value
                it[JobRunTable.scheduledAt] = due.scheduledAt
                it[JobRunTable.firedAt] = OffsetDateTime.now()
                it[JobRunTable.payload] = due.payload
                it[JobRunTable.createdAt] = OffsetDateTime.now()
            }
        }
        return inserted.insertedCount > 0
    }

    /**
     * 실패한 작업을 다시 실행하려고 claim 한다: failed → pending, retry_count + 1.
     * `status = failed and retry_count = [retryCount]` 조건으로 UPDATE 해서, 배포 중 옛·새 서버가 함께 돌아도
     * 한 곳만 가져간다 (나머지는 0행 → false).
     */
    fun claimRetry(jobType: String, runKey: String, retryCount: Int): Boolean = transaction {
        JobRunTable.update({
            (JobRunTable.jobType eq jobType) and (JobRunTable.runKey eq runKey) and
                (JobRunTable.status eq JobStatus.FAILED.value) and (JobRunTable.retryCount eq retryCount)
        }) {
            it[JobRunTable.status] = JobStatus.PENDING.value
            it[JobRunTable.retryCount] = retryCount + 1
            it[JobRunTable.firedAt] = OffsetDateTime.now()
            it[JobRunTable.finishedAt] = null
        } > 0
    }

    fun markDone(jobType: String, runKey: String) {
        transaction {
            JobRunRow.find { (JobRunTable.jobType eq jobType) and (JobRunTable.runKey eq runKey) }
                .firstOrNull()
                ?.apply {
                    status = JobStatus.DONE.value
                    finishedAt = OffsetDateTime.now()
                }
        }
    }

    fun markFailed(jobType: String, runKey: String, error: Throwable) {
        transaction {
            JobRunRow.find { (JobRunTable.jobType eq jobType) and (JobRunTable.runKey eq runKey) }
                .firstOrNull()
                ?.apply {
                    status = JobStatus.FAILED.value
                    finishedAt = OffsetDateTime.now()
                    lastError = error.message
                }
        }
    }
}
