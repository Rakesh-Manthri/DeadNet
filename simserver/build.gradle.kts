plugins {
    kotlin("jvm") version "2.0.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Pure Kotlin JVM stdlib only. The Kotlin stdlib is added automatically by the kotlin("jvm") plugin.
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.deadnet.sim.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
