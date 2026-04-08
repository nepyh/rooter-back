package com.github.nepyh.ebazi.module.database

data class DatabaseConfig(
    val driverClassName: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int
)
