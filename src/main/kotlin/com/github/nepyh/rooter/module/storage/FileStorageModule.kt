package com.github.nepyh.rooter.module.storage

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageApi
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import com.github.nepyh.rooter.module.storage.impl.s3.S3FileStorage
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Paths


enum class FileStorageType {
    LOCAL,
    S3,
    ;
}

fun FileStorageModule(appConfig: AppConfig) = module {
    val storageType = try {
        FileStorageType.valueOf(appConfig.storageType.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Storage type name \"${appConfig.storageType}\" does not exist", e)
    }

    when (storageType) {
        FileStorageType.LOCAL -> {
            single<FileStorage> {
                LocalFileStorageImpl(
                    baseDir = Paths.get(appConfig.storageBaseDir!!),
                    baseUrl = appConfig.storageBaseUrl!!
                )
            }

            single<ApiRoute> {
                LocalFileStorageApi(
                    baseDir = Paths.get(appConfig.storageBaseDir!!),
                    baseRoute = appConfig.storageBaseRoute!!.trim('/')
                )
            }
        }
        FileStorageType.S3 -> {
            single<FileStorage> {
                S3FileStorage(
                    region = appConfig.storageAwsRegion!!,
                    bucket = appConfig.storageAwsBucket!!
                )
            }.onClose { storage ->
                if (storage is S3FileStorage) {
                    storage.close()
                }
            }
        }
    }
}
