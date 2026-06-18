package com.github.nepyh.rooter.module.storage.impl.local

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.http.content.staticFiles
import java.io.File


fun LocalFileStorageApi(baseDir: String, baseRoute: String) = ApiRoute(baseRoute) {
    println(File(baseDir).absoluteFile)
    staticFiles(
        remotePath = "",
        dir = File(baseDir)
    )
}
