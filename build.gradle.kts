import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.0.0"
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
    testImplementation(kotlin("test"))

    // logging, structure, ktor
    implementation("ch.qos.logback:logback-classic:1.5.32")

    implementation("io.insert-koin:koin-ktor:$koinVersion")
    implementation("io.insert-koin:koin-logger-slf4j:$koinVersion")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")

    // database, orm
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("org.jetbrains.exposed:exposed-core:${exposedVersion}")
    implementation("org.jetbrains.exposed:exposed-dao:${exposedVersion}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${exposedVersion}")
    implementation("org.jetbrains.exposed:exposed-java-time:${exposedVersion}")
    implementation("org.mindrot:jbcrypt:0.4")
    // exposed jdbc driver using different version name
    implementation("org.jetbrains.exposed:exposed-jdbc:${exposedVersion}")

    implementation("org.postgresql:postgresql:42.7.2")
    //jwt
    implementation("com.auth0:java-jwt:4.4.0")
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
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = mainClassPath
    }
//    from(sourceSets.main.get().output)
//    configurations = listOf(project.configurations.runtimeClasspath.get())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
