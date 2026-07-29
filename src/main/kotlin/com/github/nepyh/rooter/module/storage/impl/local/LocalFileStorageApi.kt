package com.github.nepyh.rooter.module.storage.impl.local

import com.github.nepyh.rooter.common.ApiRoute
import io.ktor.server.http.content.staticFiles
import java.io.File
import java.nio.file.Path


fun LocalFileStorageApi(baseDir: Path, baseRoute: String) = ApiRoute(baseRoute) {
    staticFiles(
        remotePath = "",
        dir = baseDir.toFile()
    )
}
