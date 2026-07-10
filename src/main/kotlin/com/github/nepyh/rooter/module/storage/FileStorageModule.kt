package com.github.nepyh.rooter.module.storage

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.common.config.StorageConfig
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageApi
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import com.github.nepyh.rooter.module.storage.impl.s3.S3FileStorage
import org.koin.dsl.module
import java.nio.file.Paths


fun FileStorageModule(appConfig: AppConfig) = module {
    val storageConfig = appConfig.storageConfig

    single<FileStorage> {
        when (storageConfig) {
            is StorageConfig.LocalFileStorageConfig -> {
                LocalFileStorageImpl(
                    baseDir = Paths.get(storageConfig.baseDir),
                    baseUrl = storageConfig.baseUrl
                )
            }
            is StorageConfig.S3FileStorageConfig -> {
                S3FileStorage(
                    region = storageConfig.region,
                    bucket = storageConfig.bucket
                )
            }
        }
    }

    if (storageConfig is StorageConfig.LocalFileStorageConfig) {
        single<ApiRoute> {
            LocalFileStorageApi(
                baseDir = Paths.get(storageConfig.baseDir),
                baseRoute = storageConfig.baseUrl.trim('/')
            )
        }
    }
}
