package com.github.nepyh.rooter.module.user.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone


/** 약관·개인정보 동의 이력. append-only — 재동의·철회도 새 행으로 남긴다 (rooter-ddl #287). */
object UserAgreementTable : IntIdTable("user_agreements") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val code = varchar("code", 40)
    val version = varchar("version", 20)
    val isRequired = bool("required")
    val agreed = bool("agreed")
    val createdAt = timestampWithTimeZone("created_at")
}

class UserAgreementRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserAgreementRow>(UserAgreementTable)

    var user by UserRow referencedOn UserAgreementTable.userId
    var code by UserAgreementTable.code
    var version by UserAgreementTable.version
    var isRequired by UserAgreementTable.isRequired
    var agreed by UserAgreementTable.agreed
    var createdAt by UserAgreementTable.createdAt
}
