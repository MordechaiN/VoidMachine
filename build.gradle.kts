/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 * Created by Mordechai Neeman.
 *
 * https://github.com/MordechaiNeeman/VoidMachine
 * Licensed under the MIT License — see LICENSE for details.
 */
plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.voidmachine"
version = "1.1.0-beta"
description = "VoidMachine — a brutal item-sink ritual machine for Paper servers."

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://nexus.scarsz.me/content/groups/public/")
    maven("https://jitpack.io")
}

dependencies {
    // Paper API — Minecraft 1.21.4 (latest stable as of release).
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Floodgate (Geyser) — required only at compile time so we can detect
    // Bedrock players cleanly when the plugin is present.
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")

    // DiscordSRV — optional. Wired via reflection at runtime, so a compileOnly
    // dependency is sufficient and we never crash when it is absent.
    compileOnly("com.discordsrv:discordsrv:1.28.0")

    // Database stack — shaded and relocated to avoid clashes with other plugins.
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")

    compileOnly("org.jetbrains:annotations:26.0.1")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-parameters"))
    }

    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("VoidMachine-${project.version}.jar")
        minimize {
            exclude(dependency("com.zaxxer:HikariCP:.*"))
            exclude(dependency("org.mariadb.jdbc:mariadb-java-client:.*"))
        }
        relocate("com.zaxxer.hikari", "com.voidmachine.lib.hikari")
        relocate("org.mariadb", "com.voidmachine.lib.mariadb")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}
