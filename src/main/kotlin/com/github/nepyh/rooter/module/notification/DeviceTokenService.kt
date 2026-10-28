package com.github.nepyh.rooter.module.notification

import com.github.nepyh.rooter.module.notification.dto.DeviceTokenRegisterRequest
import com.github.nepyh.rooter.module.notification.dto.DeviceTokenResponse
import com.github.nepyh.rooter.module.notification.exception.DeviceTokenValidationException
import com.github.nepyh.rooter.module.notification.model.UserDeviceTokenTable
import com.github.nepyh.rooter.module.notification.push.PushMessage
import com.github.nepyh.rooter.module.notification.push.PushResult
import com.github.nepyh.rooter.module.notification.push.PushSender
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime


class DeviceTokenService(private val pushSender: PushSender) {

    private val logger = LoggerFactory.getLogger(DeviceTokenService::class.java)

    /**
     * 토큰 기준 upsert. 앱이 로그인 직후·실행할 때마다 보내도 행이 늘지 않는다.
     * 다른 계정이 쓰던 토큰이면 user_id 를 지금 계정으로 옮긴다 — 같은 폰에서 계정을 바꾸면 이전 계정 알림이 오지 않게.
     */
    fun register(userId: Int, request: DeviceTokenRegisterRequest): DeviceTokenResponse {
        val token = request.token.trim()
        if (token.length > MAX_TOKEN_LENGTH || !EXPO_PUSH_TOKEN.matches(token)) {
            throw DeviceTokenValidationException.InvalidPushTokenException()
        }

        transaction {
            // created_at 은 upsert 의 insert 에만 DB 기본값으로 들어가고, 갱신 때는 그대로 남는다
            UserDeviceTokenTable.upsert(UserDeviceTokenTable.token) {
                it[UserDeviceTokenTable.userId] = userId
                it[UserDeviceTokenTable.token] = token
                it[UserDeviceTokenTable.platform] = request.platform
                it[UserDeviceTokenTable.lastSeenAt] = OffsetDateTime.now()
            }
        }
        return DeviceTokenResponse(token = token, platform = request.platform)
    }

    /** 로그아웃: 모든 기기의 JWT 가 무효가 되므로 그 사용자의 토큰도 전부 지운다. */
    fun removeAll(userId: Int) {
        transaction {
            UserDeviceTokenTable.deleteWhere { UserDeviceTokenTable.userId eq userId }
        }
    }

    /**
     * 사용자의 모든 기기로 푸시를 보내고, 발송기가 알려준 죽은 토큰을 지운다.
     * 외부 HTTP 호출 동안 DB 커넥션을 잡지 않도록 발송은 트랜잭션 밖에서 한다.
     */
    suspend fun sendToUser(userId: Int, message: PushMessage): PushResult {
        val tokens = transaction {
            UserDeviceTokenTable.selectAll()
                .where { UserDeviceTokenTable.userId eq userId }
                .map { it[UserDeviceTokenTable.token] }
        }
        if (tokens.isEmpty()) return PushResult.EMPTY

        val result = pushSender.send(tokens, message)
        if (result.invalidTokens.isNotEmpty()) {
            transaction {
                UserDeviceTokenTable.deleteWhere { UserDeviceTokenTable.token inList result.invalidTokens }
            }
            logger.info("푸시 토큰 정리: userId={}, removed={}", userId, result.invalidTokens.size)
        }
        return result
    }

    companion object {
        private const val MAX_TOKEN_LENGTH = 255 // DDL: varchar(255)
        // Expo 가 발급하는 형식. 예전 SDK 는 ExponentPushToken, 최신 문서는 ExpoPushToken 표기도 쓴다
        private val EXPO_PUSH_TOKEN = Regex("""^(ExponentPushToken|ExpoPushToken)\[[^\[\]\s]+]$""")
    }
}
