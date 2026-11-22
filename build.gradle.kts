import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Libraries attached to the light test project so fixtures can use List, Flow, etc.
val fixtureLibraries: Configuration by configurations.creating { isTransitive = false }

dependencies {
    fixtureLibraries("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    fixtureLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    intellijPlatform {
        androidStudio(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.kotlin")
        // Android Studio 2026.2 does not bundle JCEF; it is the Marketplace plugin "Web Browser (JCEF)", published per OS/arch.
        plugin("com.intellij.modules.jcef:${providers.gradleProperty("jcefPluginVersion").get()}-${jcefPlatform()}")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

fun jcefPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "arm64" else "x86_64"
    return when {
        os.contains("mac") -> "mac-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, providers.gradleProperty("platformVersion"))
        }
    }
}

// Web UI: built with npm and packaged under resources/webui. Pass -PskipWebUi to reuse webui/dist as is.
val webUiDir = layout.projectDirectory.dir("webui")
val skipWebUi = providers.gradleProperty("skipWebUi").isPresent

val npmInstall = tasks.register<Exec>("npmInstall") {
    workingDir(webUiDir)
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.files(webUiDir.file("package.json"), webUiDir.file("package-lock.json"))
    outputs.dir(webUiDir.dir("node_modules"))
    enabled = !skipWebUi
}

val buildWebUi = tasks.register<Exec>("buildWebUi") {
    dependsOn(npmInstall)
    workingDir(webUiDir)
    commandLine("npm", "run", "build")
    inputs.dir(webUiDir.dir("src"))
    inputs.files(
        webUiDir.file("index.html"),
        webUiDir.file("package.json"),
        webUiDir.file("vite.config.ts"),
        webUiDir.file("tsconfig.json"),
    )
    outputs.dir(webUiDir.dir("dist"))
    enabled = !skipWebUi
}

tasks.processResources {
    dependsOn(buildWebUi)
    from(webUiDir.dir("dist")) { into("webui") }
}

tasks.test {
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    val libraries = files(fixtureLibraries)
    inputs.files(libraries)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dclassgraph.fixtureLibraries=${libraries.asPath}") }
}

// Optional: ./gradlew runIde -PrunIdeProject=/path/to/project opens that project directly.
tasks.runIde {
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}
