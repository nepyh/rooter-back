import com.github.nepyh.rooter.module.storage.impl.local.LocalFileStorageImpl
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists


@OptIn(ExperimentalPathApi::class)
class LocalFileStorageSpec : FileStorageSpec(
    makeStorage = { LocalFileStorageImpl(workingDir, "/") },
    beforeSetup = { if (workingDir.exists()) workingDir.deleteRecursively() },
) {
    companion object {
        private val workingDir = Paths.get(System.getProperty("user.dir"), "build/test-run")
    }
}
