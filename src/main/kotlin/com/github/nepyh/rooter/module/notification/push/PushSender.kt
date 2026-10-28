package com.github.nepyh.rooter.module.notification.push

/** 기기에 뜨는 푸시 한 건. data 는 앱이 알림을 눌렀을 때 이동할 대상 등 부가 정보다. */
data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap()
)

/**
 * @property sent 발송 서비스가 받아들인 건수 (실제 기기 도달 여부는 아님)
 * @property invalidTokens 더는 쓸 수 없는 토큰 (앱 삭제 등). 호출한 쪽이 DB 에서 지운다
 */
data class PushResult(
    val sent: Int,
    val invalidTokens: List<String>
) {
    companion object {
        val EMPTY = PushResult(sent = 0, invalidTokens = emptyList())
    }
}

/**
 * 푸시 발송기. 발송 실패로 호출한 쪽(알림 저장·스케줄러 Job)까지 실패하면 안 되므로
 * 구현체는 네트워크 오류·서버 오류에 예외를 던지지 않고 [PushResult] 에 실패로 집계한다.
 */
interface PushSender {
    suspend fun send(tokens: List<String>, message: PushMessage): PushResult
}
