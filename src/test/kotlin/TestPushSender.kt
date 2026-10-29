import com.github.nepyh.rooter.module.notification.push.PushMessage
import com.github.nepyh.rooter.module.notification.push.PushResult
import com.github.nepyh.rooter.module.notification.push.PushSender


/** 푸시를 보내지 않아야 하는 테스트용 — 호출되면 실패한다. */
val noPushSender = object : PushSender {
    override suspend fun send(tokens: List<String>, message: PushMessage): PushResult = error("푸시가 발송되면 안 됨")
}

/** 보낸 토큰·메시지를 기록하고, [invalidTokens] 에 든 토큰은 죽은 토큰으로 돌려주는 가짜 발송기. */
class RecordingPushSender(private val invalidTokens: Set<String> = emptySet()) : PushSender {
    val sent = mutableListOf<Pair<List<String>, PushMessage>>()

    override suspend fun send(tokens: List<String>, message: PushMessage): PushResult {
        sent += tokens to message
        val invalid = tokens.filter { it in invalidTokens }
        return PushResult(sent = tokens.size - invalid.size, invalidTokens = invalid)
    }
}
