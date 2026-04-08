plugins {
    kotlin("jvm") version "2.3.0"
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
    // exposed jdbc driver using different version name
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")

    implementation("org.postgresql:postgresql:42.7.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}