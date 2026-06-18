package com.github.nepyh.rooter.module.storage

import com.github.nepyh.rooter.common.ApiRoute
import com.github.nepyh.rooter.config.AppConfig
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageApi
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import org.koin.core.qualifier.named
import org.koin.dsl.module


val fileStorageModule = module {
    single<FileStorage> {
        if (get<AppConfig>().storageType == "local") {
            LocalFileStorageImpl("run/store", baseUrl = "/files")
        } else {
            throw IllegalStateException("Injected AppConfig.storageType config is have a wrong value")
        }
    }

    single<ApiRoute?>(named("localFileStorageApi")) {
        if (get<AppConfig>().storageType == "local") {
            LocalFileStorageApi(baseDir = "run/store", baseRoute = "files")
        } else {
            null
        }
    }
}
