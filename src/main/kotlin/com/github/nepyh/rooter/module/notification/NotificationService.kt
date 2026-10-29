package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.module.notification.dto.NotificationListResponse
import com.github.nepyh.rooter.module.notification.dto.NotificationResponse
import com.github.nepyh.rooter.module.notification.dto.ReadAllResponse
import com.github.nepyh.rooter.module.notification.dto.UnreadCountResponse
import com.github.nepyh.rooter.module.notification.exception.NotificationException
import com.github.nepyh.rooter.module.notification.model.NotificationTable
import com.github.nepyh.rooter.module.notification.model.NotificationType
import com.github.nepyh.rooter.module.notification.push.PushMessage
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

const val NOTIFICATION_MAX_PAGE_SIZE = 50
const val NOTIFICATION_DEFAULT_PAGE_SIZE = 20

class NotificationService(
    private val deviceTokenService: DeviceTokenService,
    private val notificationSettingService: NotificationSettingService
) {

    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * 알림 한 건을 알림함에 남기고, 그 종류의 푸시 설정이 켜져 있으면 사용자의 모든 기기로 푸시를 보낸다.
     * 저장은 트랜잭션 안에서, 발송은 트랜잭션 밖에서 한다 — 푸시가 실패하거나 느려도 알림함 기록은 남고 DB 커넥션을 잡지 않는다.
     * 설정을 꺼도 알림함에는 남는다 (rooter-ddl notifications 주석).
     *
     * @return 저장한 알림 id
     */
    suspend fun notify(
        userId: Int,
        type: NotificationType,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Int {
        val savedTitle = title.take(MAX_TITLE_LENGTH)
        val savedBody = body.take(MAX_BODY_LENGTH)

        val (notificationId, pushEnabled) = transaction {
            val id = NotificationTable.insertAndGetId {
                it[NotificationTable.userId] = userId
                it[NotificationTable.type] = type
                it[NotificationTable.title] = savedTitle
                it[NotificationTable.body] = savedBody
                it[NotificationTable.data] = json.encodeToString(data)
            }.value
            id to isPushEnabled(userId, type)
        }

        if (pushEnabled) {
            // 앱이 푸시를 눌렀을 때 알림함 항목을 읽음 처리할 수 있게 id 를 함께 싣는다
            val pushData = data + (NOTIFICATION_ID_KEY to notificationId.toString())
            val result = deviceTokenService.sendToUser(userId, PushMessage(savedTitle, savedBody, pushData))
            logger.info("알림 발송: userId={}, type={}, notificationId={}, sent={}", userId, type, notificationId, result.sent)
        }
        return notificationId
    }

    /** 본인 알림을 최신순으로 limit 개. cursor 는 앞 페이지 응답의 nextCursor (id 커서라 조회 중 새 알림이 생겨도 안 밀린다). */
    fun list(userId: Int, cursor: Int? = null, limit: Int = NOTIFICATION_DEFAULT_PAGE_SIZE): NotificationListResponse = transaction {
        var condition: Op<Boolean> = NotificationTable.userId eq userId
        if (cursor != null) condition = condition and (NotificationTable.id less cursor)

        val rows = NotificationTable.selectAll()
            .where { condition }
            .orderBy(NotificationTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val page = rows.take(limit)

        NotificationListResponse(
            notifications = page.map { it.toResponse() },
            nextCursor = if (rows.size > limit) page.last()[NotificationTable.id].value else null
        )
    }

    fun unreadCount(userId: Int): UnreadCountResponse = transaction {
        UnreadCountResponse(
            unreadCount = NotificationTable.selectAll()
                .where { (NotificationTable.userId eq userId) and NotificationTable.readAt.isNull() }
                .count()
        )
    }

    /** 이미 읽은 알림이면 처음 읽은 시각을 그대로 둔다. 남의 알림이거나 없으면 같은 404. */
    fun markRead(userId: Int, notificationId: Int): NotificationResponse = transaction {
        val mine = (NotificationTable.id eq notificationId) and (NotificationTable.userId eq userId)
        NotificationTable.update({ mine and NotificationTable.readAt.isNull() }) {
            it[readAt] = OffsetDateTime.now()
        }
        NotificationTable.selectAll().where { mine }.singleOrNull()?.toResponse()
            ?: throw NotificationException.NotFoundException()
    }

    fun markAllRead(userId: Int): ReadAllResponse = transaction {
        val updated = NotificationTable.update({ (NotificationTable.userId eq userId) and NotificationTable.readAt.isNull() }) {
            it[readAt] = OffsetDateTime.now()
        }
        ReadAllResponse(updatedCount = updated)
    }

    private fun isPushEnabled(userId: Int, type: NotificationType): Boolean {
        val settings = notificationSettingService.get(userId)
        return when (type) {
            NotificationType.TASK_REMINDER -> settings.taskReminderEnabled
        }
    }

    private fun ResultRow.toResponse(): NotificationResponse {
        val readAt = this[NotificationTable.readAt]
        return NotificationResponse(
            id = this[NotificationTable.id].value,
            type = this[NotificationTable.type],
            title = this[NotificationTable.title],
            body = this[NotificationTable.body],
            data = parseData(this[NotificationTable.data]),
            read = readAt != null,
            readAt = readAt?.toString(),
            createdAt = this[NotificationTable.createdAt].toString()
        )
    }

    /** 서버가 쓴 값만 들어 있지만, 손으로 넣은 행이 깨져 있어도 목록 전체가 실패하지 않게 빈 값으로 읽는다. */
    private fun parseData(raw: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrElse {
            logger.warn("알림 data 를 읽지 못해 빈 값으로 응답합니다.")
            emptyMap()
        }

    companion object {
        const val NOTIFICATION_ID_KEY = "notificationId"
        private const val MAX_TITLE_LENGTH = 100 // DDL: varchar(100)
        private const val MAX_BODY_LENGTH = 500  // DDL: varchar(500)
        private val json = Json
    }
}
