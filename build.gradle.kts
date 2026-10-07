/*
 * VoidMachine — a forbidden artifact for Paper servers.
 * Created by Mordechai Neeman. Licensed under the MIT License — see LICENSE.
 */
plugins {
    java
}

group = "com.voidmachine"
version = providers.gradleProperty("pluginVersion").get()
description = "A cinematic, crash-safe item sacrifice ritual for Paper servers."

val paperApiVersion: String = providers.gradleProperty("paperApiVersion").get()
val javaRelease: Int = providers.gradleProperty("javaRelease").get().toInt()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaRelease))
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        content { includeGroup("io.papermc.paper") }
    }
}

dependencies {
    // Provided by the server at runtime. VoidMachine ships with zero bundled libraries.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.117.0")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaRelease)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-parameters"))
    }

    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    jar {
        archiveFileName.set("VoidMachine-${project.version}.jar")
        from(rootProject.file("LICENSE")) { into("META-INF") }
        manifest {
            attributes(
                "Implementation-Title" to "VoidMachine",
                "Implementation-Version" to project.version.toString(),
            )
        }
        // Reproducible archives: stable ordering, no timestamps.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    named<Jar>("sourcesJar") {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    test {
        useJUnitPlatform {
            // Benchmarks are opt-in: ./gradlew benchmark
            excludeTags("benchmark")
        }
        maxHeapSize = "1g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    register<Test>("benchmark") {
        description = "Runs the opt-in performance benchmarks (tagged 'benchmark')."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("benchmark") }
        maxHeapSize = "1g"
        testLogging {
            events("passed", "failed")
            showStandardStreams = true
        }
    }
}
