import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform")
}

group = "dev.ebataille"

// The git tag wins over this value when CI builds a release, so `git tag v0.3.0 && git push
// --tags` is enough to ship 0.3.0: no commit whose only purpose is to bump a number, and no
// release zip labelled with the previous version because the bump was forgotten. This constant
// stays the version local builds carry.
val declaredVersion = "0.2.0"
version = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { it.matches(Regex("""v\d+\.\d+\.\d+.*""")) }
    ?.removePrefix("v")
    ?: declaredVersion

dependencies {
    intellijPlatform {
        // Le SDK de reference : WebStorm, qui embarque le plugin JavaScript/TypeScript.
        webstorm("2025.2.3")
        // Refactorings PSI et service TypeScript.
        bundledPlugin("JavaScript")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.ebataille.idebridge"
        name = "PSI Bridge for AI Agents"
        version = project.version.toString()
        description = """
            Exposes the IDE's PSI refactorings, resolved references and diagnostics to coding
            agents over MCP, so they stop rebuilding all of it out of grep, sed and tsc.
            Works with Claude Code and any other MCP client.
            Not affiliated with Anthropic or JetBrains.
        """.trimIndent()
        vendor {
            name = "Edouard Bataille"
        }
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
    // Pas de signature/publication pour l'instant : usage local.
}

tasks {
    runIde {
        // L'IDE de dev demarre sur ce projet meme, pratique pour tester le bridge.
        argumentProviders.add(CommandLineArgumentProvider { listOf(file("testdata").absolutePath) })
    }
}
