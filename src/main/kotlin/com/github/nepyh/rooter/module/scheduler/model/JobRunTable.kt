package com.github.nepyh.rooter.module.scheduler.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * scheduler 실행 로그 겸 outbox 테이블.
 * (job_type, run_key) 유니크 키로 중복 실행을 방지한다.
 */
object JobRunTable : IntIdTable("job_runs") {
    val jobType = varchar("job_type", 50)
    val runKey = varchar("run_key", 255)
    val status = varchar("status", 20).default(JobStatus.PENDING.value)
    val scheduledAt = timestampWithTimeZone("scheduled_at")
    val firedAt = timestampWithTimeZone("fired_at").nullable()
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val retryCount = integer("retry_count").default(0)
    val lastError = text("last_error").nullable()
    val payload = text("payload").default("{}")
    val createdAt = timestampWithTimeZone("created_at")

    init {
        uniqueIndex(jobType, runKey)
    }
}

class JobRunRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<JobRunRow>(JobRunTable)

    var jobType by JobRunTable.jobType
    var runKey by JobRunTable.runKey
    var status by JobRunTable.status
    var scheduledAt by JobRunTable.scheduledAt
    var firedAt by JobRunTable.firedAt
    var finishedAt by JobRunTable.finishedAt
    var retryCount by JobRunTable.retryCount
    var lastError by JobRunTable.lastError
    var payload by JobRunTable.payload
    var createdAt by JobRunTable.createdAt
}
