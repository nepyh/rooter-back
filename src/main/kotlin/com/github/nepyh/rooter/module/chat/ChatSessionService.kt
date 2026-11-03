package com.github.nepyh.rooter.module.chat

import com.github.nepyh.rooter.module.chat.dto.ChatSessionResponse
import com.github.nepyh.rooter.module.chat.dto.ChatTurnResponse
import com.github.nepyh.rooter.module.chat.exception.ChatTurnNotFoundException
import com.github.nepyh.rooter.module.chat.exception.ChatValidationException
import com.github.nepyh.rooter.module.chat.model.ChatSessionRow
import com.github.nepyh.rooter.module.chat.model.ChatSessionTable
import com.github.nepyh.rooter.module.chat.model.ChatTurnFeedback
import com.github.nepyh.rooter.module.chat.model.ChatTurnRow
import com.github.nepyh.rooter.module.chat.model.ChatTurnTable
import com.github.nepyh.rooter.module.planboard.model.DailyPlanTable
import com.github.nepyh.rooter.module.planboard.model.PlanBoardTable
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime

/**
 * 챗봇 세션·메시지 관리 (#342) — 세션 목록·생성·삭제, 세션 대화 조회, 답변 좋아요·싫어요, 질문·답 한 쌍 삭제.
 * 메시지 보내기는 [ChatService.sendMessage].
 */
class ChatSessionService {

    /** 내 세션 목록. 마지막으로 대화한 세션부터 */
    fun listSessions(userId: Int): List<ChatSessionResponse> = transaction {
        ChatSessionRow.find { ChatSessionTable.userId eq userId }
            .orderBy(ChatSessionTable.updatedAt to SortOrder.DESC, ChatSessionTable.id to SortOrder.DESC)
            .map { it.toResponse() }
    }

    /** 빈 세션을 만든다. 제목은 첫 메시지를 보낼 때 정해진다 */
    fun createSession(userId: Int): ChatSessionResponse = transaction {
        val now = OffsetDateTime.now()
        ChatSessionRow.new {
            user = UserRow[userId]
            createdAt = now
            updatedAt = now
        }.toResponse()
    }

    /** 세션과 그 안의 대화를 지운다. 챗봇이 이미 바꾼 계획은 그대로다 */
    fun deleteSession(userId: Int, sessionId: Int) = transaction {
        val session = requireOwnedSession(userId, sessionId)
        ChatTurnTable.deleteWhere { ChatTurnTable.sessionId eq session.id }
        session.delete()
    }

    fun getSessionTurns(userId: Int, sessionId: Int): List<ChatTurnResponse> = transaction {
        requireOwnedSession(userId, sessionId)
        turnsOf(sessionId).map { it.toResponse() }
    }

    /** 챗봇 답변에 좋아요·싫어요를 남긴다. null 이면 취소. 사용자 메시지에는 400 */
    fun setFeedback(userId: Int, turnId: Int, feedback: ChatTurnFeedback?): ChatTurnResponse = transaction {
        val turn = requireOwnedTurn(userId, turnId)
        if (turn.role != "assistant") throw ChatValidationException.FeedbackNotAllowedException()
        turn.feedback = feedback
        turn.toResponse()
    }

    /**
     * 질문·답 한 쌍을 지운다. 사용자 메시지를 주면 그 바로 다음 챗봇 답변까지, 챗봇 답변을 주면 그 바로 앞 사용자 메시지까지.
     * 지운 대화는 다음 메시지의 AI 히스토리에서도 빠진다. 챗봇이 이미 바꾼 계획은 되돌리지 않는다.
     * @return 지운 메시지 id
     */
    fun deleteTurn(userId: Int, turnId: Int): List<Int> = transaction {
        val turn = requireOwnedTurn(userId, turnId)
        val pairRole = if (turn.role == "user") "assistant" else "user"
        val partner = neighborOf(turn, after = turn.role == "user")?.takeIf { it.role == pairRole }
        val ids = listOfNotNull(turn.id.value, partner?.id?.value)
        ChatTurnTable.deleteWhere { ChatTurnTable.id inList ids }
        ids
    }

    /**
     * 같은 대화에서 바로 뒤([after]) 또는 바로 앞 메시지. 세션이 있으면 같은 세션,
     * 세션이 없는 예전 대화면 같은 날 계획의 세션 없는 대화 안에서 찾는다
     */
    private fun neighborOf(turn: ChatTurnRow, after: Boolean): ChatTurnRow? {
        val sameConversation: Op<Boolean> = turn.sessionKey?.let { ChatTurnTable.sessionId eq it }
            ?: ((ChatTurnTable.dailyPlanId eq turn.dailyPlanKey) and ChatTurnTable.sessionId.isNull())
        val position = if (after) ChatTurnTable.id greater turn.id.value else ChatTurnTable.id less turn.id.value
        return ChatTurnRow.find { sameConversation and position }
            .orderBy(ChatTurnTable.id to if (after) SortOrder.ASC else SortOrder.DESC)
            .limit(1)
            .firstOrNull()
    }

    /** 본인 메시지. 소유는 그 메시지가 본 날 계획의 플랜보드 주인으로 판단한다 (세션 없는 예전 대화도 같은 기준). 트랜잭션 안에서 부른다 */
    private fun requireOwnedTurn(userId: Int, turnId: Int): ChatTurnRow {
        val owned = (ChatTurnTable innerJoin DailyPlanTable innerJoin PlanBoardTable)
            .select(ChatTurnTable.id)
            .where { (ChatTurnTable.id eq turnId) and (PlanBoardTable.userId eq userId) }
            .any()
        if (!owned) throw ChatTurnNotFoundException()
        return ChatTurnRow[turnId]
    }
}
