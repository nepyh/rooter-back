package com.github.nepyh.ebazi.module.database

import org.koin.dsl.module
import io.ktor.server.application.*
import org.koin.ktor.ext.getProperty

val databaseModule = module {
    single { DatabaseManager }
}

fun Application.installDatabaseModule(dbConfig: DatabaseConfig) {
    DatabaseManager.init(
        dbConfig
    )
}
