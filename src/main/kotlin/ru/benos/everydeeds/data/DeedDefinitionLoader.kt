package ru.benos.everydeeds.data

import com.mojang.serialization.JsonOps
import net.minecraft.core.HolderLookup
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.StrictJsonParser
import net.minecraft.util.profiling.ProfilerFiller
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.deed.DeedDefinition

/**
 * Loads [DeedDefinition]s from `data/<namespace>/everydeeds/deeds/<set>/<path>.json`.
 *
 * Parsing uses registry-aware ops so rewards can reference data-driven content. Loaded definitions
 * are published to [DeedDefinitions]; expansion into concrete deeds happens once the server has
 * finished reloading (recipes and tags must be available), see [DeedIndexBuilder].
 */
class DeedDefinitionLoader(
    private val registries: HolderLookup.Provider
) : SimplePreparableReloadListener<Map<Identifier, DeedDefinition>>() {

    override fun prepare(manager: ResourceManager, profiler: ProfilerFiller): Map<Identifier, DeedDefinition> {
        val ops = registries.createSerializationContext(JsonOps.INSTANCE)
        val result = LinkedHashMap<Identifier, DeedDefinition>()

        for ((location, resource) in LISTER.listMatchingResources(manager)) {
            val id = LISTER.fileToId(location)
            runCatching {
                resource.openAsReader().use { reader ->
                    DeedDefinition.CODEC.parse(ops, StrictJsonParser.parse(reader))
                        .ifSuccess { definition -> result[id] = definition }
                        .ifError { error -> EveryDeeds.LOGGER.error("Couldn't parse deed definition '{}': {}", id, error.message()) }
                }
            }.onFailure { failure ->
                EveryDeeds.LOGGER.error("Couldn't read deed definition '{}' from '{}'", id, location, failure)
            }
        }

        return result
    }

    override fun apply(preparations: Map<Identifier, DeedDefinition>, manager: ResourceManager, profiler: ProfilerFiller) {
        DeedDefinitions.replace(preparations)
        EveryDeeds.LOGGER.info("Loaded {} deed definitions in sets {}", preparations.size, DeedDefinitions.sets())
    }

    companion object {
        val LISTENER_ID: Identifier = Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, "deed_definitions")
        private val LISTER: FileToIdConverter = FileToIdConverter.json("${EveryDeeds.MOD_ID}/deeds")
    }
}

/** Latest loaded definitions, keyed by id (`namespace:set/path`). */
object DeedDefinitions {
    @Volatile
    private var definitions: Map<Identifier, DeedDefinition> = emptyMap()

    fun replace(loaded: Map<Identifier, DeedDefinition>) {
        definitions = loaded.toMap()
    }

    fun all(): Map<Identifier, DeedDefinition> = definitions

    /** Definitions whose set (first path segment) is [set]. Sets merge across namespaces. */
    fun inSet(set: String): Map<Identifier, DeedDefinition> =
        definitions.filterKeys { id -> setOf(id) == set }

    fun sets(): Set<String> = definitions.keys.map(::setOf).toSortedSet()

    fun setOf(id: Identifier): String = id.path.substringBefore('/')
}
