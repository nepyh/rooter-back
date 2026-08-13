import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.0.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "com.github.nepyh"
version = "1.0-SNAPSHOT"

val koinVersion = "4.0.0"
val ktorVersion = "3.4.2"
val exposedVersion = "1.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // logging
    implementation("ch.qos.logback:logback-classic:1.5.32")

    // koin
    implementation("io.insert-koin:koin-ktor:$koinVersion")
    implementation("io.insert-koin:koin-logger-slf4j:$koinVersion")

    // ktor
    // TODO 의존성이 증식하고 있음. 조만간 gradle 버전 카탈로그로 바꾸는게 나을지도
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-swagger:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")

    // database, orm
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("org.jetbrains.exposed:exposed-core:${exposedVersion}")
    implementation("org.jetbrains.exposed:exposed-dao:${exposedVersion}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${exposedVersion}")
    implementation("org.jetbrains.exposed:exposed-java-time:${exposedVersion}")

    implementation("org.postgresql:postgresql:42.7.2")

    implementation("org.mindrot:jbcrypt:0.4")

    //jwt
    implementation("com.auth0:java-jwt:4.4.0")

    // test dependencies
    testImplementation("io.kotest:kotest-runner-junit5:6.2.0")
    testImplementation("io.kotest:kotest-assertions-core:6.2.0")
    testImplementation("io.kotest:kotest-property:6.2.0")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

val mainClassPath = "com.github.nepyh.rooter.MainKt"

application {
    mainClass.set(mainClassPath)
}
tasks.named<JavaExec>("run") {
    val envFile = File(projectDir, ".env")

    if (envFile.exists()) {
        envFile.bufferedReader().use { reader ->
            val properties = Properties()
            properties.load(reader)
            properties.forEach { (key, value) ->
                environment(key.toString(), value.toString())
            }
        }
    } else {
        logger.warn(".env 파일을 프로젝트 루트에서 찾을수 없습니다.")
        logger.warn("리드미에 적힌대로 했음?")
    }

    systemProperties(System.getProperties().mapKeys { it.key.toString() })
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = mainClassPath
    }
//    from(sourceSets.main.get().output)
//    configurations = listOf(project.configurations.runtimeClasspath.get())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
}
