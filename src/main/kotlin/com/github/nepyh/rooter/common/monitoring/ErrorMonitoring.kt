package com.github.nepyh.rooter.common.monitoring

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

data class MonitoringConfig(val dsn: String, val environment: String, val release: String)

enum class ErrorSource { HTTP, STARTUP, SCHEDULER, PUSH }

/** 원문 예외·요청·사용자 정보를 SDK 에 넘기지 않는다. 코드 위치만 오류 분석에 사용한다. */
object ErrorMonitoring {
    private val logger = LoggerFactory.getLogger(ErrorMonitoring::class.java)

    fun initialize(config: MonitoringConfig) {
        try {
            Sentry.init(options(config))
        } catch (_: Exception) {
            Sentry.close()
            // SDK 오류에 DSN 이 포함될 수 있으므로 예외를 로깅하지 않는다.
            logger.warn("Sentry 설정을 확인해 주세요. 오류 수집을 비활성화합니다.")
        }
    }

    fun capture(cause: Throwable, source: ErrorSource, status: Int = 500) {
        if (status !in 500..599 || !Sentry.isEnabled()) return
        val chain = causeChain(cause)
        if (chain.any { it is CancellationException }) return
        try {
            val event = SentryEvent().apply {
                level = SentryLevel.ERROR
                setTag("error.source", source.name.lowercase())
                exceptions = chain.reversed().map { error ->
                    SentryException().apply {
                        type = error.javaClass.simpleName
                        module = error.javaClass.packageName
                        value = "[exception message removed]"
                        stacktrace = SentryStackTrace(error.stackTrace.take(100).reversed().map { frame ->
                            SentryStackFrame().apply {
                                module = frame.className
                                function = frame.methodName
                                filename = frame.fileName
                                lineno = frame.lineNumber.takeIf { it > 0 }
                            }
                        })
                    }
                }
            }
            Sentry.captureEvent(event)
        } catch (_: Exception) {
            // 관측 도구의 실패로 원래 응답이나 잡 실행 결과를 바꾸지 않는다.
            logger.warn("Sentry 오류 이벤트를 전달하지 못했습니다.")
        }
    }

    fun close() = Sentry.close()

    internal fun options(config: MonitoringConfig) = SentryOptions().apply {
        dsn = config.dsn.trim()
        isEnabled = config.dsn.isNotBlank()
        environment = safeMetadata(config.environment)
        release = safeMetadata(config.release)
        isEnableExternalConfiguration = false
        isSendDefaultPii = false
        isAttachServerName = false
        isAttachStacktrace = false
        isAttachThreads = false
        isSendModules = false
        isEnableAutoSessionTracking = false
        isSendClientReports = false
        isEnableScopePersistence = false
        isEnableUncaughtExceptionHandler = false
        isEnableShutdownHook = false
        integrations.clear()
        maxBreadcrumbs = 0
        maxAttachmentSize = 0
        tracesSampleRate = 0.0
        profilesSampleRate = 0.0
        profileSessionSampleRate = 0.0
        logs.isEnabled = false
        metrics.isEnabled = false
        setBeforeSendTransaction { _, _ -> null }
        setBeforeSend { event, hint ->
            // 빈 첨부 파일도 파일명에 개인정보를 포함할 수 있다.
            hint.clearAttachments()
            sanitizedEvent(event, config)
        }
    }
}

/** SDK 가 scope 를 합친 뒤에도 허용 필드만 새 객체로 복사한다. SQL·메시지·headers 등을 지우는 denylist 가 아니다. */
internal fun sanitizedEvent(event: SentryEvent, config: MonitoringConfig): SentryEvent? {
    val source = event.getTag("error.source")
    if (source !in ErrorSource.entries.map { it.name.lowercase() } || event.exceptions.isNullOrEmpty()) return null
    return SentryEvent(event.timestamp).apply {
        eventId = event.eventId
        platform = "java"
        level = SentryLevel.ERROR
        environment = safeMetadata(config.environment)
        release = safeMetadata(config.release)
        setTag("error.source", source!!)
        exceptions = event.exceptions!!.take(8).map { error ->
            SentryException().apply {
                type = safeCodeName(error.type) ?: "Exception"
                module = safeCodeName(error.module)
                value = "[exception message removed]"
                stacktrace = SentryStackTrace(error.stacktrace?.frames.orEmpty().takeLast(100).map { frame ->
                    SentryStackFrame().apply {
                        module = safeCodeName(frame.module)
                        function = safeCodeName(frame.function)
                        filename = safeCodeName(frame.filename?.substringAfterLast('/')?.substringAfterLast('\\'))
                        lineno = frame.lineno?.takeIf { it > 0 }
                        isInApp = module?.startsWith("com.github.nepyh.rooter.") == true
                    }
                })
            }
        }
    }
}

private fun causeChain(cause: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(cause) { it.cause }.takeWhile { seen.add(it) }.take(8).toList()
}

private fun safeCodeName(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Za-z0-9_.$<>-]{1,200}")) }
private fun safeMetadata(value: String): String? = value.takeIf { it.matches(Regex("[A-Za-z0-9_.@/+:-]{1,200}")) }
