package com.github.nepyh.rooter.common.monitoring

import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import io.sentry.kotlin.SentryContext
import io.sentry.protocol.TransactionNameSource
import kotlinx.coroutines.withContext

private val RouteTemplateKey = AttributeKey<String>("sentry.routeTemplate")
private val TransactionKey = AttributeKey<ITransaction>("sentry.transaction")

/**
 * 응답이 실제로 나간 뒤에 트랜잭션을 닫는다. 예외로 끝난 요청은 핸들러가 끝난 시점에는 아직 상태가 없고
 * StatusPages 가 400·500 을 정한 다음에야 응답 코드가 생긴다.
 */
private val TransactionFinisher = createApplicationPlugin("SentryTransactionFinisher") {
    on(ResponseSent) { call ->
        val transaction = call.attributes.getOrNull(TransactionKey) ?: return@on
        val template = call.attributes.getOrNull(RouteTemplateKey) ?: return@on // 매칭 안 된 경로는 닫지 않고 버린다
        transaction.setName("${call.request.httpMethod.value} $template", TransactionNameSource.ROUTE)
        val status = call.response.status()?.value
        if (status != null) transaction.setData("http.response.status_code", status)
        transaction.status = status?.let { SpanStatus.fromHttpStatusCode(it, SpanStatus.UNKNOWN_ERROR) }
            ?: SpanStatus.UNKNOWN_ERROR
        transaction.finish()
    }
}

/** 수집하지 않는 경로. 헬스체크는 로드밸런서가 계속 불러 무료 플랜 한도만 쓴다. */
private val UNTRACED_PATHS = setOf("/api/health")

internal const val UNMATCHED_ROUTE = "<unmatched>"

/**
 * 요청마다 Sentry 트랜잭션을 하나 연다 (Ktor 는 Sentry 공식 연동이 없다).
 * - 이름은 라우트 템플릿(`GET /api/plan-tasks/{taskId}`)이다. 실제 경로·쿼리스트링은 싣지 않는다
 * - [SentryContext] 로 코루틴이 스레드를 옮겨도 같은 트랜잭션에 쿼리 span 이 붙는다
 * - 어느 라우트에도 맞지 않은 요청(404 스캔 등)은 보내지 않는다 — 한도만 쓰고 이름으로 구분할 수 없다
 * - 샘플링 비율이 0 이거나 DSN 이 없으면 설치하지 않는다
 */
fun Application.installRequestTracing() {
    if (!ErrorMonitoring.isTracingEnabled()) return

    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        call.attributes.put(RouteTemplateKey, routeTemplate(call.route))
    }

    install(TransactionFinisher)

    intercept(ApplicationCallPipeline.Monitoring) {
        val method = call.request.httpMethod
        if (method == HttpMethod.Options || call.request.path() in UNTRACED_PATHS) {
            proceed()
            return@intercept
        }
        val scopes = Sentry.forkedRootScopes("http.server")
        val transaction = scopes.startTransaction(
            TransactionContext("${method.value} $UNMATCHED_ROUTE", TransactionNameSource.ROUTE, "http.server"),
            TransactionOptions().apply { isBindToScope = true }
        )
        call.attributes.put(TransactionKey, transaction)
        withContext(SentryContext(scopes)) { proceed() }
    }
}

/** 라우트 트리를 거슬러 올라가 경로 템플릿만 만든다. `authenticate` 같은 비경로 노드는 건너뛴다. */
internal fun routeTemplate(route: RoutingNode): String {
    val segments = generateSequence(route) { it.parent }.toList().asReversed().mapNotNull { node ->
        when (val selector = node.selector) {
            is PathSegmentConstantRouteSelector -> selector.value
            is PathSegmentParameterRouteSelector -> "{${selector.name}}"
            is PathSegmentOptionalParameterRouteSelector -> "{${selector.name}?}"
            is PathSegmentTailcardRouteSelector -> "{${selector.name.ifEmpty { "..." }}...}"
            is PathSegmentWildcardRouteSelector -> "*"
            is HttpMethodRouteSelector -> null
            else -> null
        }
    }
    return "/" + segments.joinToString("/")
}
