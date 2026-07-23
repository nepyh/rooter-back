package com.github.nepyh.rooter

import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.EnvironmentMode
import com.github.nepyh.rooter.module.AppModule
import com.github.nepyh.rooter.module.configureAppModule
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.io.FileInputStream


fun Application.appEntryModule() {
    val appConfig = AppConfig.fromApplicationConfig(environment.config)

    install(Koin) {
        slf4jLogger()
        modules(AppModule(appConfig))
    }

    initFirebase(appConfig)

    install(ContentNegotiation) {
        json()
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        if (appConfig.environment == EnvironmentMode.PROD) {
            appConfig.corsAllowedHosts.forEach { hostName ->
                allowHost(hostName, schemes = listOf("http", "https"))
            }
        } else {
            anyHost()
        }

        allowCredentials = true
    }

    configureAppModule()
}

private fun Application.initFirebase(appConfig: AppConfig) {
    val credentialsPath = appConfig.firebaseCredentialsPath ?: return

    runCatching {
        val credentials = FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) }
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .build()

        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        }
    }.onFailure {
        log.warn("Firebase 초기화 실패, push 알림 없이 계속 진행", it)
    }
}
