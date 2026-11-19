package com.github.nepyh.rooter.common

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 요청 처리를 Netty 의 call 스레드가 아니라 [Dispatchers.IO] 에서 한다.
 *
 * 서비스는 Exposed `transaction { }` 으로 DB 를 블로킹 호출하고, JWT 검증(`UserJwtValidator`)도 요청마다 사용자를 조회한다.
 * Netty call 스레드는 CPU 수에 맞춰 만들어져 1 vCPU 컨테이너에서는 1개뿐이라, 거기서 블로킹하면
 * 한 요청이 DB 응답을 기다리는 동안 나머지 요청이 전부 줄을 선다 (DB 커넥션은 놀고 응답 시간만 늘어난다 — #361 부하 테스트).
 * IO 디스패처는 블로킹 작업용으로 64개까지 스레드를 늘리므로, 동시에 DB 를 기다리는 요청 수가 커넥션 풀 크기까지 올라간다.
 *
 * 파이프라인 맨 앞(Setup)에 두어 인증·라우팅·핸들러·StatusPages 가 모두 이 디스패처에서 돈다.
 */
fun Application.installBlockingCallDispatcher() {
    intercept(ApplicationCallPipeline.Setup) {
        withContext(Dispatchers.IO) { proceed() }
    }
}
