package com.github.nepyh.rooter.common.config

import io.ktor.server.config.ApplicationConfig


data class AppConfig(
    val environment: EnvironmentMode,

    // database related
    val jdbcUrl: String,
    val dbUsername: String,
    val dbPassword: String,
    val dbMaxPoolSize: Int,

    // cors related
    val corsAllowedHosts: List<String>,
    val corsMaxAgeSeconds: Long,

    // storage related
    val storageType: String,
    val storageBaseDir: String?,
    val storageBaseUrl: String?,
    val storageBaseRoute: String?,

    // jwt related
    val jwtSecret: String,
    val jwtIssuer: String,

    // nice (나이스 교육정보 개방포털) related
    val niceApiKey: String,
    val niceBaseUrl: String
) {
    companion object {
        fun fromApplicationConfig(config: ApplicationConfig): AppConfig {
            val envMode = EnvironmentMode.valueOf(
                config.property("ktor.deployment.environment").getString().uppercase()
            )

            val allowedHosts = config.property("cors.allowedHosts")
                .getString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { rawHost ->
                    rawHost
                        .replace("https://", "")
                        .replace("http://", "")
                        .removeSuffix("/")
                }

            return AppConfig(
                environment = envMode,

                jdbcUrl = config.property("database.jdbcUrl").getString(),
                dbUsername = config.property("database.dbUser").getString(),
                dbPassword = config.property("database.dbPassword").getString(),
                dbMaxPoolSize = config.property("database.dbMaxPoolSize").getString().toInt(),

                corsAllowedHosts = allowedHosts,
                corsMaxAgeSeconds = config.property("cors.maxAgeSeconds").getString().toLong(),

                storageType = config.property("storage.type").getString(),
                storageBaseDir = config.propertyOrNull("storage.baseDir")?.getString(),
                storageBaseUrl = config.propertyOrNull("storage.baseUrl")?.getString(),
                storageBaseRoute = config.propertyOrNull("storage.baseRoute")?.getString(),

                jwtSecret = config.property("jwt.secret").getString(),
                jwtIssuer = config.property("jwt.issuer").getString(),

                niceApiKey = config.property("nice.apiKey").getString(),
                niceBaseUrl = config.propertyOrNull("nice.baseUrl")?.getString()
                    ?: "https://open.neis.go.kr/hub"
            )
        }
    }
}