package com.github.nepyh.rooter.module.scheduler

import com.github.nepyh.rooter.module.scheduler.model.JobRunRow
import com.github.nepyh.rooter.module.scheduler.model.JobRunTable
import com.github.nepyh.rooter.module.scheduler.model.JobStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime

class JobRunRepo {

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
