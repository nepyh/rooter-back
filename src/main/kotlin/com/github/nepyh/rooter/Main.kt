package com.github.nepyh.rooter

import io.ktor.server.netty.EngineMain
import com.github.nepyh.rooter.common.monitoring.ErrorMonitoring
import com.github.nepyh.rooter.common.monitoring.ErrorSource

fun main(args: Array<String>) {
    try {
        EngineMain.main(args)
    } catch (cause: Throwable) {
        ErrorMonitoring.capture(cause, ErrorSource.STARTUP)
        throw cause
    } finally {
        ErrorMonitoring.close()
    }
}
