package com.github.nepyh.rooter.module.storage

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.config.AppConfig
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageApi
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Paths


enum class FileStorageType {
    LOCAL,
    ;
}

val fileStorageModule = module {
    single<FileStorage> {
        val appConfig: AppConfig = get() // TODO this is really bad. function? (in new ticket)
        val storageType = FileStorageType.valueOf(appConfig.storageType.uppercase())

        when (storageType) {
            FileStorageType.LOCAL -> {
                LocalFileStorageImpl(
                    baseDir = Paths.get(appConfig.storageBaseDir!!),
                    baseUrl = appConfig.storageBaseUrl!!
                )
            }
        }
    }

    single<ApiRoute?>(named("localFileStorageApi")) {
        val appConfig: AppConfig = get()
        val storageType = FileStorageType.valueOf(appConfig.storageType.uppercase())

        when (storageType) {
            FileStorageType.LOCAL -> {
                LocalFileStorageApi(
                    baseDir = Paths.get(appConfig.storageBaseDir!!),
                    baseRoute = appConfig.storageBaseRoute!!.trim('/')
                )
            }
//            else -> {
//                null // TODO also this
//            }
        }
    }
}
