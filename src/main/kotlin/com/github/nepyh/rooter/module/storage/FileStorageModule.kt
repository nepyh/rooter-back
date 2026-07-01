package com.github.nepyh.rooter.module.storage

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.common.config.AppConfig
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageApi
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import org.koin.dsl.module
import java.nio.file.Paths


enum class FileStorageType {
    LOCAL,
    ;
}

fun FileStorageModule(appConfig: AppConfig) = module {
    val storageType = try {
        FileStorageType.valueOf(appConfig.storageType.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Storage type name \"${appConfig.storageType}\" does not exist", e)
    }

    single<FileStorage> {
        when (storageType) {
            FileStorageType.LOCAL -> {
                LocalFileStorageImpl(
                    baseDir = Paths.get(appConfig.storageBaseDir!!),
                    baseUrl = appConfig.storageBaseUrl!!
                )
            }
        }
    }

    if (storageType ==  FileStorageType.LOCAL) {
        single<ApiRoute> {
            LocalFileStorageApi(
                baseDir = Paths.get(appConfig.storageBaseDir!!),
                baseRoute = appConfig.storageBaseRoute!!.trim('/')
            )
        }
    }
}
