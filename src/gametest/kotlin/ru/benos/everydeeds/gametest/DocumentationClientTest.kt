package ru.benos.everydeeds.gametest

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.DataResult
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.minecraft.SharedConstants
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.pack.PackMetadataSection
import net.minecraft.tags.TagFile
import net.minecraft.util.StrictJsonParser
import net.minecraft.world.level.storage.LevelResource
import ru.benos.everydeeds.EveryDeeds.MOD_ID
import ru.benos.everydeeds.client.deed.ClientDeedSnapshot
import ru.benos.everydeeds.data.DeedPacks
import ru.benos.everydeeds.deed.DeedDefinition
import ru.benos.everydeeds.deed.DeedGoal
import ru.benos.everydeeds.deed.DeedPredicate
import ru.benos.everydeeds.deed.DeedReward
import ru.benos.everydeeds.deed.TargetSelector
import ru.benos.everydeeds.tracking.DeedTracker
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The customization guide (`docs/customization.md`) and the example data pack (`docs/datapack-example`)
 * must describe what the mod actually reads. Every JSON example of the guide and every file of the pack
 * decodes with the codec it shows; the pack, dropped into a world's `datapacks` folder the way its README
 * says, loads on `/reload` and gives deeds for each of its files.
 *
 * The build passes the location of the `docs` folder in the `everydeeds.docs` system property.
 */
class DocumentationClientTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val docs = Path.of(checkNotNull(System.getProperty(DOCS_PROPERTY)) { "The '$DOCS_PROPERTY' system property must point to the docs folder" })

        context.worldBuilder().create().use { singleplayer ->
            singleplayer.connection.waitForChunksRender()
            context.waitFor({ ClientDeedSnapshot.isSynced }, 200)

            val guide = docs.resolve("customization.md")
            val guideExamples = examples(guide.fileName.toString(), Files.readAllLines(guide))
            check(guideExamples.isNotEmpty()) { "No JSON examples found in $guide" }
            val pack = docs.resolve("datapack-example")
            val packFiles = (listOf(pack.resolve("pack.mcmeta")) + deedFiles(pack).values).map { file ->
                Example(docs.relativize(file).joinToString("/"), parseObject(Files.readString(file), file.toString()))
            }
            verifyDecoding(singleplayer, guideExamples + packFiles)

            verifyBuiltinPackId(singleplayer)
            verifyExamplePack(context, singleplayer, pack)
        }
    }

    /** Decodes each example with the codec of what it shows, the way the mod reads such a file. */
    private fun verifyDecoding(singleplayer: TestSingleplayerContext, examples: List<Example>) {
        val failures = singleplayer.server.computeOnServer<List<String>, RuntimeException> { server ->
            val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)
            examples.mapNotNull { example ->
                decode(example.json, ops).error().map { error -> "${example.source}: ${error.message()}" }.orElse(null)
            }
        }
        check(failures.isEmpty()) { "Examples the mod can't read:\n" + failures.joinToString("\n") }
    }

    /** The guide tells how to turn the built-in pack off with `/datapack disable`: the id must be the real one. */
    private fun verifyBuiltinPackId(singleplayer: TestSingleplayerContext) {
        val selected = singleplayer.server.computeOnServer<Collection<String>, RuntimeException> { server -> server.packRepository.selectedIds }
        check(DeedPacks.MORE_MILESTONES.toString() in selected) { "Expected the pack '${DeedPacks.MORE_MILESTONES}' among $selected" }
    }

    /**
     * Follows the example's README: the folder goes into the world's `datapacks` folder, `/reload` picks it
     * up, the set shows up on the difficulty slider, and after switching to it each file has its deeds
     * (the milestone exactly one).
     */
    private fun verifyExamplePack(context: ClientGameTestContext, singleplayer: TestSingleplayerContext, pack: Path) {
        singleplayer.server.runOnServer<RuntimeException> { server ->
            copyFolder(pack, server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(EXAMPLE_FOLDER))
        }
        singleplayer.server.runCommand("reload")
        context.waitFor({ EXAMPLE_SET in ClientDeedSnapshot.availableSets }, 400)
        singleplayer.server.runCommand("everydeeds set $EXAMPLE_SET")
        context.waitFor({ ClientDeedSnapshot.set == EXAMPLE_SET }, 200)

        val definitions = deedFiles(pack).keys
        val deeds = singleplayer.server.computeOnServer<Map<Identifier, Int>, RuntimeException> { _ ->
            DeedTracker.index.instances.groupingBy { instance -> instance.definitionId }.eachCount()
        }
        val empty = definitions.filter { id -> (deeds[id] ?: 0) == 0 }
        check(empty.isEmpty()) { "Example files without deeds: $empty (deeds per file: $deeds)" }
        check(deeds.keys == definitions) { "The example set must consist of the example files only: ${deeds.keys}" }
        val milestone = Identifier.fromNamespaceAndPath(EXAMPLE_NAMESPACE, "$EXAMPLE_SET/milestones/logs_1000")
        check(deeds[milestone] == 1) { "The example milestone must be one deed, got ${deeds[milestone]}" }
    }

    /** Picks the codec by the fields of the example, as a reader recognizes it. An example nothing matches is an error. */
    private fun decode(json: JsonObject, ops: DynamicOps<JsonElement>): DataResult<*> {
        val keys = json.keySet()
        return when {
            "pack" in keys -> PackMetadataSection.forPackType(PackType.SERVER_DATA).codec().parse(ops, json.get("pack"))
                .flatMap(::supportsCurrentVersion)
            "values" in keys -> TagFile.CODEC.parse(ops, json)
            "category" in keys -> DeedDefinition.CODEC.parse(ops, json)
            "title" in keys -> ComponentSerialization.CODEC.parse(ops, json.get("title"))
            keys.any { key -> key in TARGET_FORMS } -> TargetSelector.CODEC.parse(ops, json)
            keys.any { key -> key in REWARD_FIELDS } -> DeedReward.CODEC.parse(ops, json)
            keys.any { key -> key in GOAL_FIELDS } -> DeedGoal.CODEC.parse(ops, json)
            "type" in keys -> DeedPredicate.CODEC.parse(ops, json)
            else -> DataResult.error<Unit> { "Unknown kind of example, fields $keys" }
        }
    }

    private fun supportsCurrentVersion(section: PackMetadataSection): DataResult<PackMetadataSection> {
        val current = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)
        return if (section.supportedFormats().isValueInRange(current)) DataResult.success(section)
        else DataResult.error { "Pack formats ${section.supportedFormats()} don't include the game's $current" }
    }

    /** A JSON object from the docs and where it comes from (file and line), for messages. */
    private class Example(val source: String, val json: JsonObject)

    /**
     * The JSON examples of a Markdown file: every ```json block, and every line of a ```jsonc block. A jsonc
     * block lists one-line alternatives with comments; a `"field": value` line is read as an object of that field.
     */
    private fun examples(file: String, lines: List<String>): List<Example> {
        val examples = ArrayList<Example>()
        var index = 0
        while (index < lines.size) {
            val opening = lines[index].trim()
            if (!opening.startsWith("```")) {
                index++
                continue
            }
            val start = index + 1
            var end = start
            while (end < lines.size && lines[end].trim() != "```") end++
            val body = lines.subList(start, end)
            when (opening.removePrefix("```")) {
                "json" -> examples += Example("$file:${start + 1}", parseObject(body.joinToString("\n"), "$file:${start + 1}"))
                "jsonc" -> body.forEachIndexed { offset, line ->
                    val code = withoutComment(line).trim()
                    val source = "$file:${start + offset + 1}"
                    if (code.isNotEmpty()) examples += Example(source, parseObject(if (code.startsWith("{")) code else "{$code}", source))
                }
            }
            index = end + 1
        }
        return examples
    }

    /** The line without its `//` comment; a `//` inside a string stays. */
    private fun withoutComment(line: String): String {
        var inString = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                inString && char == '\\' -> index++
                char == '"' -> inString = !inString
                !inString && line.startsWith("//", index) -> return line.substring(0, index)
            }
            index++
        }
        return line
    }

    private fun parseObject(text: String, source: String): JsonObject =
        runCatching { StrictJsonParser.parse(text).asJsonObject }
            .getOrElse { failure -> throw IllegalStateException("$source: not a JSON object: ${failure.message}", failure) }

    /** Deed files of a data pack by id: `data/<namespace>/everydeeds/deeds/<path>.json` is `<namespace>:<path>`. */
    private fun deedFiles(pack: Path): Map<Identifier, Path> {
        val namespaces = Files.list(pack.resolve("data")).use { folders -> folders.toList() }
        return namespaces.flatMap { namespace ->
            val deeds = namespace.resolve(MOD_ID).resolve("deeds")
            if (!Files.isDirectory(deeds)) return@flatMap emptyList<Pair<Identifier, Path>>()
            Files.walk(deeds).use { paths -> paths.filter { path -> path.toString().endsWith(".json") }.toList() }.map { file ->
                val path = deeds.relativize(file).joinToString("/").removeSuffix(".json")
                Identifier.fromNamespaceAndPath(namespace.fileName.toString(), path) to file
            }
        }.toMap()
    }

    private fun copyFolder(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private companion object {
        const val DOCS_PROPERTY = "everydeeds.docs"

        /** What the example pack adds, and the folder it is copied to (the README leaves the name to the reader). */
        const val EXAMPLE_SET = "example_custom"
        const val EXAMPLE_NAMESPACE = "mypack"
        const val EXAMPLE_FOLDER = "everydeeds-example"

        val TARGET_FORMS = setOf("id", "tag", "all")
        val REWARD_FIELDS = setOf("experience", "items", "function")
        val GOAL_FIELDS = setOf("count", "variant", "required")
    }
}
