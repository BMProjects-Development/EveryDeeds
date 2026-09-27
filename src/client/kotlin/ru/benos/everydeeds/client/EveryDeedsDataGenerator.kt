package ru.benos.everydeeds.client

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput
import net.minecraft.data.metadata.PackMetadataGenerator
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.EveryDeeds.translatable
import ru.benos.everydeeds.client.datagen.DeedBlockTagProvider
import ru.benos.everydeeds.client.datagen.DeedEffectTagProvider
import ru.benos.everydeeds.client.datagen.DeedEntityTypeTagProvider
import ru.benos.everydeeds.client.datagen.DeedItemTagProvider
import ru.benos.everydeeds.client.datagen.DeedSetProvider
import ru.benos.everydeeds.client.datagen.DeedStructureTagProvider
import ru.benos.everydeeds.client.datagen.MoreMilestonesProvider
import ru.benos.everydeeds.data.DeedPacks

class EveryDeedsDataGenerator : DataGeneratorEntrypoint {
    override fun onInitializeDataGenerator(fabricDataGenerator: FabricDataGenerator) {
        val pack = fabricDataGenerator.createPack()
        pack.addProvider(::DeedBlockTagProvider)
        pack.addProvider(::DeedItemTagProvider)
        pack.addProvider(::DeedEntityTypeTagProvider)
        pack.addProvider(::DeedStructureTagProvider)
        pack.addProvider(::DeedEffectTagProvider)
        pack.addProvider(::DeedSetProvider)

        // Optional content ships as built-in data packs; their pack.mcmeta is generated for the game's current format.
        val moreMilestones = fabricDataGenerator.createBuiltinResourcePack(DeedPacks.MORE_MILESTONES)
        moreMilestones.addProvider(::MoreMilestonesProvider)
        moreMilestones.addProvider { output: FabricPackOutput ->
            PackMetadataGenerator.forFeaturePack(output, "pack.${EveryDeeds.MOD_ID}.more_milestones.description".translatable)
        }
    }
}
