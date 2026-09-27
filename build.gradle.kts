import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.20"
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
    id("maven-publish")
}

version = property("mod_version") as String
group = property("maven_group") as String

base { archivesName.set(property("archives_base_name") as String) }

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("everydeeds") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

loom {
    runs {
        configureEach {
            // Native crashes (driver, SDL) leave a readable report next to the test run instead of vanishing.
            vmArg("-XX:ErrorFile=hs_err_%p.log")
        }
    }
}

fabricApi {
    configureDataGeneration { client = true }

    // Separate `gametest` source set: never packaged into the release jar.
    configureTests {
        createSourceSet = true
        modId = "everydeeds-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

loom {
    runs {
        named("clientGameTest") {
            // DocumentationClientTest checks the examples of the customization guide against the mod.
            vmArg("-Deverydeeds.docs=${file("docs").absolutePath}")
        }
    }
}

repositories {
    // Mod Menu (optional integration: config button in the mod list).
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${prop("kotlin_loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_version")}")

    // Optional: compiled against for the integration, present in dev runs, never bundled.
    "clientCompileOnly"("com.terraformersmc:modmenu:${prop("modmenu_version")}")
    "gametestCompileOnly"("com.terraformersmc:modmenu:${prop("modmenu_version")}")
    runtimeOnly("com.terraformersmc:modmenu:${prop("modmenu_version")}")
}

tasks.processResources {
    // Resolved at configuration time: reading `project` inside the expand closure happens at execution time.
    val expandProperties = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to prop("minecraft_version"),
        "loader_version" to prop("loader_version"),
        "kotlin_loader_version" to prop("kotlin_loader_version")
    )

    inputs.properties(expandProperties)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(expandProperties)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    // Resolved at configuration time: touching `project` inside the rename closure runs at execution time.
    val licenseSuffix = base.archivesName.get()
    from("LICENSE.txt") {
        rename { "LICENSE_$licenseSuffix.txt" }
    }
}

fun prop(key: String): String =
    project.property(key).toString()