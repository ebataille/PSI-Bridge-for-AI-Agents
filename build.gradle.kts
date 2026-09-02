import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform")
}

group = "dev.ebataille"

// The git tag wins over this value when CI builds a release, so `git tag v0.3.0 && git push
// --tags` is enough to ship 0.3.0: no commit whose only purpose is to bump a number, and no
// release zip labelled with the previous version because the bump was forgotten. This constant
// stays the version local builds carry.
val declaredVersion = "0.2.1"
version = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { it.matches(Regex("""v\d+\.\d+\.\d+.*""")) }
    ?.removePrefix("v")
    ?: declaredVersion

dependencies {
    intellijPlatform {
        // Le SDK de reference : WebStorm, qui embarque le plugin JavaScript/TypeScript.
        webstorm("2026.2.1")
        // Refactorings PSI et service TypeScript.
        bundledPlugin("JavaScript")
        // La recherche structurelle a quitte le coeur de la plateforme en 2026.2 pour devenir un
        // plugin bundle : sans cette dependance, structural_replace ne compile plus.
        bundledPlugin("intellij.structuralSearch.plugin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
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
            sinceBuild = "262"
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
