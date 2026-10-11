import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins")
                .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
        )
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "233"
            untilBuild = "262.*"
        }
    }
}

intellijPlatform {
    instrumentCode = false
    // The plugin has no settings pages; this step only added minutes to every build.
    buildSearchableOptions = false

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.6.1"
    }

    register("bumpVersion") {
        // Resolved at configuration time: calling file() inside doLast captures the
        // script object, which the configuration cache cannot serialize.
        val propsFile = layout.projectDirectory.file("gradle.properties").asFile
        doLast {
            val content = propsFile.readText()
            val regex = Regex("""pluginVersion\s*=\s*(\d+)\.(\d+)\.(\d+)""")
            val match = regex.find(content) ?: return@doLast
            val major = match.groupValues[1].toInt()
            val minor = match.groupValues[2].toInt()
            val patch = match.groupValues[3].toInt()
            val next = "$major.$minor.${patch + 1}"
            propsFile.writeText(content.replace(match.value, "pluginVersion = $next"))
            println("Version bumped: $major.$minor.$patch -> $next")
        }
    }

    // Bump after a successful build so failed builds don't burn version numbers.
    // The plugin just built keeps the current version; the next build gets the bumped one.
    named("buildPlugin") {
        finalizedBy("bumpVersion")
    }
}
