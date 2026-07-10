package com.github.nepyh.rooter.common.config

import io.ktor.server.config.*


enum class FileStorageType {
    LOCAL,
    S3,
    ;
}

sealed class StorageConfig(val storageType: FileStorageType) {
    data class LocalFileStorageConfig(
        val storageBaseDir: String,
        val storageBaseUrl: String,
        val storageBaseRoute: String
    ) : StorageConfig(FileStorageType.LOCAL) {
        companion object {
            fun fromApplicationConfig(config: ApplicationConfig) =
                LocalFileStorageConfig(
                    config.property("storage.baseDir").getString(),
                    config.property("storage.baseUrl").getString(),
                    config.property("storage.baseRoute").getString()
                )
        }
    }

    data class S3FileStorageConfig(
        val region: String,
        val bucket: String
    ) : StorageConfig(FileStorageType.S3) {
        companion object {
            fun fromApplicationConfig(config: ApplicationConfig) =
                S3FileStorageConfig(
                    config.property("storage.awsRegion").getString(),
                    config.property("storage.awsBucket").getString(),
                )
        }
    }

    companion object {
        fun fromApplicationConfig(config: ApplicationConfig): StorageConfig {
            val storageType = FileStorageType.valueOf(
                config.property("storage.type").getString().uppercase()
            )

            return when (storageType) {
                FileStorageType.LOCAL -> {
                    LocalFileStorageConfig.fromApplicationConfig(config)
                }
                FileStorageType.S3 -> {
                    S3FileStorageConfig.fromApplicationConfig(config)
                }
            }
        }
    }
}
