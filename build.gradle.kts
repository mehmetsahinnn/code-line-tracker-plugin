plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
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
            untilBuild = "261.*"
        }
    }
}

intellijPlatform {
    instrumentCode = false
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }

    register("bumpVersion") {
        doLast {
            val propsFile = file("gradle.properties")
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

    named("buildPlugin") {
        dependsOn("bumpVersion")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
