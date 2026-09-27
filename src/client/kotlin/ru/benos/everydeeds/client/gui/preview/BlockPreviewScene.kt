package ru.benos.everydeeds.client.gui.preview

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.material.FluidState
import org.joml.Vector3f

/**
 * Everything needed to draw one block "as placed": all of its parts (both halves of a bed or a door),
 * their block entities (chest lids, banners, the enchanting-table book) and, for fluids, the fluid.
 */
class BlockPreviewScene(
    val parts: List<Part>,
    val fluid: FluidState?,
    /** Centre of the scene's bounding box, in block units. */
    val center: Vector3f,
    /** Largest bounding-box side, in blocks: used to fit the scene into a cell. */
    val extent: Float,
    /**
     * Fire, plants, portals, torches: blocks without collision are flat sprites or planes that the world
     * draws unshaded. Directional 3D light would darken planes turned away from it, so they get flat light.
     */
    val flatLighting: Boolean
) {
    class Part(val state: BlockState, val offset: BlockPos, val blockEntity: BlockEntity?) {
        /** Resolved lazily on the render thread and reused every frame. */
        var model: BlockModelRenderState? = null
    }

    companion object {
        fun build(defaultState: BlockState, level: ClientLevel): BlockPreviewScene {
            val state = showcaseState(defaultState)
            val parts = partsOf(state).map { (partState, offset) ->
                Part(partState, offset, createBlockEntity(partState, offset, level))
            }

            val fluid = if (state.block is LiquidBlock) state.fluidState else null
            val minX = parts.minOf { it.offset.x }.toFloat()
            val minY = parts.minOf { it.offset.y }.toFloat()
            val minZ = parts.minOf { it.offset.z }.toFloat()
            val maxX = parts.maxOf { it.offset.x } + 1f
            val maxY = parts.maxOf { it.offset.y } + 1f
            val maxZ = parts.maxOf { it.offset.z } + 1f
            val center = Vector3f((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
            val extent = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
            val flatLighting = fluid == null && parts.all { part ->
                runCatching { part.state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty }.getOrDefault(false)
            }
            return BlockPreviewScene(parts, fluid, center, extent, flatLighting)
        }

        /**
         * Default states are not always the most readable: a lone glass pane or iron bars is a thin
         * post. Connect east and west sides so panes, bars, fences and walls read as what they are.
         * Fire is the exception: its sides are flames climbing neighbouring blocks, and its default
         * state (burning on the ground, flames on every side) is the fire everyone knows.
         */
        private fun showcaseState(state: BlockState): BlockState {
            if (state.block is FireBlock) return state
            var result = state
            for (property in state.properties) {
                if (property.name != "east" && property.name != "west") continue
                result = connected(result, property)
            }
            return result
        }

        private fun connected(state: BlockState, property: Property<*>): BlockState =
            when (property) {
                is BooleanProperty -> state.setValue(property, true)
                // Walls use LOW/TALL, redstone wire SIDE/UP: any value other than "none" is connected.
                else -> withFirstValueNot(state, property, "none")
            }

        private fun <T : Comparable<T>> withFirstValueNot(state: BlockState, property: Property<T>, excludedName: String): BlockState {
            val value = property.possibleValues.firstOrNull { value -> property.getName(value) != excludedName } ?: return state
            return state.setValue(property, value)
        }

        /** Multi-block blocks are shown whole: the other half of doors, tall plants and beds is added. */
        private fun partsOf(state: BlockState): List<Pair<BlockState, BlockPos>> {
            val base = listOf(state to BlockPos.ZERO)

            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                val lower = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                val upper = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                return listOf(lower to BlockPos.ZERO, upper to BlockPos.ZERO.above())
            }

            if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                val facing: Direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                val foot = state.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                val head = state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                return listOf(foot to BlockPos.ZERO, head to BlockPos.ZERO.relative(facing))
            }

            return base
        }

        private fun createBlockEntity(state: BlockState, offset: BlockPos, level: ClientLevel): BlockEntity? {
            val block = state.block as? EntityBlock ?: return null
            return runCatching {
                block.newBlockEntity(offset, state)?.also { blockEntity -> blockEntity.setLevel(level) }
            }.getOrNull()
        }
    }
}
