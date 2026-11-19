package com.github.nepyh.rooter.common.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.p6spy.engine.spy.P6DataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseManager {
    /** [traceQueries] 면 P6Spy 로 감싸 쿼리마다 Sentry span 을 남긴다 (SQL 문만, 바인딩 값 제외 — resources/spy.properties). */
    fun init(config: DatabaseConfig, traceQueries: Boolean = false) {
        val config = HikariConfig().apply {
            driverClassName = config.driverClassName
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maxPoolSize
        }
        val dataSource = HikariDataSource(config)

        // injecting database to exposed lib
        Database.connect(if (traceQueries) P6DataSource(dataSource) else dataSource)
    }
}
