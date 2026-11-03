package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.ChatSessionResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import com.github.nepyh.rooter.module.chat.exception.ChatSessionNotFoundException
import com.github.nepyh.rooter.module.chat.model.ChatSessionRow
import com.github.nepyh.rooter.module.chat.model.ChatSessionTable
import com.github.nepyh.rooter.module.chat.model.ChatTurnRow
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanRow
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.time.OffsetDateTime

/** 세션 제목 최대 길이. chat_sessions.title(varchar 100) 보다 짧게 — 목록 한 줄에 보이도록 */
internal const val SESSION_TITLE_MAX = 30

/** AI 가 준 제목이 없거나 비면 첫 메시지 앞부분으로 (#342) */
internal fun sessionTitleOf(aiTitle: String?, firstMessage: String): String {
    aiTitle?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let { return it.take(SESSION_TITLE_MAX) }
    val line = firstMessage.trim().lineSequence().first().trim()
    return if (line.length > SESSION_TITLE_MAX) line.take(SESSION_TITLE_MAX - 1) + "…" else line
}

/** 본인 세션. 아니면 [ChatSessionNotFoundException]. 트랜잭션 안에서 부른다 */
internal fun requireOwnedSession(userId: Int, sessionId: Int): ChatSessionRow =
    ChatSessionRow.findById(sessionId)?.takeIf { it.user.id.value == userId } ?: throw ChatSessionNotFoundException()

/**
 * 메시지를 이어 붙일 세션. [sessionId] 를 주면 그 세션(본인 것만), 안 주면 이 날 계획으로 마지막에 대화한 세션 —
 * 세션을 모르는 예전 앱이 날짜마다 대화 하나로 쓰던 것과 같게 동작한다. 그것도 없으면 null (저장할 때 새로 만든다).
 * 트랜잭션 안에서 부른다.
 */
internal fun sessionToContinue(userId: Int, dailyPlanId: Int, sessionId: Int?): ChatSessionRow? {
    if (sessionId != null) return requireOwnedSession(userId, sessionId)
    return (ChatTurnTable innerJoin ChatSessionTable)
        .select(ChatSessionTable.id)
        .where { (ChatTurnTable.dailyPlanId eq dailyPlanId) and (ChatSessionTable.userId eq userId) }
        .orderBy(ChatTurnTable.id to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.let { ChatSessionRow[it[ChatSessionTable.id]] }
}

/** 세션의 대화를 순서대로. 트랜잭션 안에서 부른다 */
internal fun turnsOf(sessionId: Int): List<ChatTurnRow> =
    ChatTurnRow.find { ChatTurnTable.sessionId eq sessionId }
        .orderBy(ChatTurnTable.id to SortOrder.ASC)
        .toList()

internal class SavedTurns(val sessionId: Int, val sessionTitle: String?, val userTurnId: Int, val assistantTurnId: Int)

/**
 * 질문·답 한 쌍을 세션에 저장한다. 세션이 없으면([sessionId] null) 새로 만들고, 제목이 아직 없으면 정한다.
 * 세션의 updated_at 은 마지막 대화 시각으로 올린다. 트랜잭션 안에서 부른다.
 */
internal fun saveTurns(
    userId: Int,
    sessionId: Int?,
    dailyPlanId: Int,
    userMessage: String,
    reply: String,
    aiTitle: String?
): SavedTurns {
    val now = OffsetDateTime.now()
    val session = sessionId?.let { id -> ChatSessionRow.findById(id) ?: throw ChatSessionNotFoundException() }
        ?: ChatSessionRow.new {
            user = UserRow[userId]
            createdAt = now
            updatedAt = now
        }
    if (session.title == null) session.title = sessionTitleOf(aiTitle, userMessage)
    session.updatedAt = now

    val dailyPlan = DailyPlanRow[dailyPlanId]
    val userTurn = ChatTurnRow.new {
        this.dailyPlan = dailyPlan
        this.session = session
        role = "user"
        content = userMessage
        createdAt = now
    }
    val assistantTurn = ChatTurnRow.new {
        this.dailyPlan = dailyPlan
        this.session = session
        role = "assistant"
        content = reply
        createdAt = now
    }
    return SavedTurns(session.id.value, session.title, userTurn.id.value, assistantTurn.id.value)
}

internal fun ChatSessionRow.toResponse() = ChatSessionResponse(
    id = id.value,
    title = title,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

internal fun ChatTurnRow.toResponse() = ChatTurnResponse(
    id = id.value,
    sessionId = sessionKey?.value,
    dailyPlanId = dailyPlanKey.value,
    role = role,
    content = content,
    feedback = feedback,
    createdAt = createdAt.toString()
)
