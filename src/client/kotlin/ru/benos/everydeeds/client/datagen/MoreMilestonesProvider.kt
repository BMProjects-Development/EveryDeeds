package ru.benos.everydeeds.client.datagen

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput
import net.fabricmc.fabric.api.datagen.v1.provider.FabricCodecDataProvider
import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.resources.Identifier
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.deed.DeedDefinition
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer

/**
 * Contents of the "More milestones" built-in data pack ([ru.benos.everydeeds.data.DeedPacks.MORE_MILESTONES]):
 * [Milestones.MORE] for every built-in set. The pack is on by default and can be turned off per world
 * like any data pack.
 */
class MoreMilestonesProvider(
    output: FabricPackOutput,
    registries: CompletableFuture<HolderLookup.Provider>
) : FabricCodecDataProvider<DeedDefinition>(
    output, registries, PackOutput.Target.DATA_PACK, "${EveryDeeds.MOD_ID}/deeds", DeedDefinition.CODEC
) {
    override fun getName(): String = "EveryDeeds more milestones"

    override fun configure(provider: BiConsumer<Identifier, DeedDefinition>, registries: HolderLookup.Provider) {
        for ((set, scale) in Milestones.SCALES) {
            Milestones.MORE.flatMap { tiers -> tiers.definitions(scale) }.forEach { (path, definition) ->
                provider.accept(Identifier.fromNamespaceAndPath(EveryDeeds.MOD_ID, "$set/$path"), definition)
            }
        }
    }
}
