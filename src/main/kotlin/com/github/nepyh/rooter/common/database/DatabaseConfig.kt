package com.github.nepyh.rooter.common.database

data class DatabaseConfig(
    val driverClassName: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int
)
