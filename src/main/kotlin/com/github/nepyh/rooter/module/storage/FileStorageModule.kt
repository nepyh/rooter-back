package com.github.nepyh.rooter.module.storage

import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageApi
import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import org.koin.core.qualifier.named
import org.koin.dsl.module


val fileStorageModule = module {
    single<FileStorage> { LocalFileStorageImpl("run/store", baseUrl = "files") }
    single(named("localFileStorageApi")) {
        LocalFileStorageApi(baseDir = "run/store", baseRoute = "files")
    }
}
