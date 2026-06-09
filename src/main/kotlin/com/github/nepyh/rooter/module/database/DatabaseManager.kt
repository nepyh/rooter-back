package com.github.nepyh.rooter.module.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseManager {
    fun init(config: DatabaseConfig) {
        val config = HikariConfig().apply {
            driverClassName = config.driverClassName
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maxPoolSize
        }
        val dataSource = HikariDataSource(config)

        // injecting database to exposed lib
        Database.connect(dataSource)
    }
}
