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

    // neis (school) api
    val neisApiKey: String
) {
    companion object {
        fun fromApplicationConfig(config: ApplicationConfig): AppConfig {
            val envMode = EnvironmentMode.fromString(
                config.property("ktor.deployment.environment").getString()
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

                neisApiKey = config.property("neis.apiKey").getString()
            )
        }
    }
}