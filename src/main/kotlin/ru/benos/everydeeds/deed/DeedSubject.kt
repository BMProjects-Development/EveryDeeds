package ru.benos.everydeeds.deed

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.Entity as McEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome as McBiome
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.Structure as McStructure

/**
 * The concrete object an action was performed on, carrying everything predicates and variant
 * extractors may need: the block state, the full item stack with its components, the live entity,
 * the effect with its level, or the registry key of a biome, dimension or structure.
 */
sealed interface DeedSubject {
    val category: DeedCategory
    val targetId: Identifier

    data class Block(val state: BlockState) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.BLOCKS
        override val targetId: Identifier get() = BuiltInRegistries.BLOCK.getKey(state.block)
    }

    /** [stack] must be a snapshot: callers copy it before the game mutates or consumes it. */
    data class Item(val stack: ItemStack) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.ITEMS
        override val targetId: Identifier get() = BuiltInRegistries.ITEM.getKey(stack.item)
    }

    data class Entity(val entity: McEntity) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.ENTITIES
        override val targetId: Identifier get() = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
    }

    /** Biomes are data-driven: identified by their registry key. */
    data class Biome(val key: ResourceKey<McBiome>) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.BIOMES
        override val targetId: Identifier get() = key.identifier()
    }

    data class Dimension(val key: ResourceKey<Level>) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.DIMENSIONS
        override val targetId: Identifier get() = key.identifier()
    }

    /** [level] starts at 1 (amplifier + 1). */
    data class Effect(val effect: Holder<MobEffect>, val level: Int = 1) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.EFFECTS
        override val targetId: Identifier get() = effect.unwrapKey().map { key -> key.identifier() }.orElseThrow()
    }

    data class Structure(val key: ResourceKey<McStructure>) : DeedSubject {
        override val category: DeedCategory get() = DeedCategory.STRUCTURES
        override val targetId: Identifier get() = key.identifier()
    }
}
