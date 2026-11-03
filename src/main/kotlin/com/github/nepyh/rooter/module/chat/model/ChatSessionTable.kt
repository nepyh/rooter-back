package com.github.nepyh.rooter.module.chat.model

import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * 챗봇 대화 세션 (#342). 날짜와 상관없는 유저 전체 목록이고, 세션마다 대화(chat_turns)가 따로 쌓인다.
 * 제목은 세션 첫 메시지를 처리할 때 정한다 (그전엔 null). updated_at 은 마지막 대화 시각이라 목록 정렬에 쓴다.
 */
object ChatSessionTable : IntIdTable("chat_sessions") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 100).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

class ChatSessionRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatSessionRow>(ChatSessionTable)

    var user by UserRow referencedOn ChatSessionTable.userId
    var title by ChatSessionTable.title
    var createdAt by ChatSessionTable.createdAt
    var updatedAt by ChatSessionTable.updatedAt
}
