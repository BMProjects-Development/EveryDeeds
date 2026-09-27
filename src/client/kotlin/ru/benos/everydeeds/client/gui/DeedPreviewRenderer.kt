package ru.benos.everydeeds.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.entity.state.BoatRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.util.Util
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.benos.everydeeds.EveryDeeds
import ru.benos.everydeeds.client.gui.preview.BlockPreviewRenderState
import ru.benos.everydeeds.client.gui.preview.BlockPreviewScene
import ru.benos.everydeeds.client.gui.preview.ModelBounds
import kotlin.math.max
import kotlin.math.min

/**
 * Previews of deed targets, in two modes:
 *
 * - [renderIcon]: what the grid shows. Flat inventory-style icons (blocks at the inventory angle);
 *   blocks without an item and mobs get a static model instead.
 * - [renderLive]: what the hover focus and the details window show. A spinning 3D block scene
 *   (both halves of beds/doors, block entities, fluids), a spinning item, a turning mob.
 *
 * Biomes, structures and effects have no model and look the same in both modes: a colour swatch,
 * an explorer-map marker, a HUD icon.
 *
 * Preview entities are client-only instances that are never added to the level. Anything that cannot
 * be rendered falls back to the flat icon, and every such failure is logged once per target.
 */
object DeedPreviewRenderer {
    private const val BLOCK_SPIN_DEGREES_PER_SECOND = 40f
    private const val ITEM_SPIN_DEGREES_PER_SECOND = 60f
    private const val ENTITY_SPIN_DEGREES_PER_SECOND = 25f
    private const val BLOCK_TILT_DEGREES = 30f
    private const val BLOCK_ICON_YAW_DEGREES = 225f
    private const val ENTITY_ICON_YAW_DEGREES = 200f
    private const val ITEM_TILT_DEGREES = 20f
    private const val BUST_FRACTION = 0.62f
    private const val BUST_MIN_HEIGHT = 1.3f
    private const val WIDTH_ALLOWANCE = 1.8f
    private const val ITEM_BLOCK_SCALE = 1.9f
    private const val ITEM_FLAT_SCALE = 1.1f
    private const val ITEM_CENTER_OFFSET = 0.25f

    private const val EFFECT_ICON_SIZE = 18
    private const val MAP_ICON_SIZE = 8
    private const val MAP_PAPER = 0xFFD9C79A.toInt()
    private const val MAP_BORDER = 0xFF8A7650.toInt()

    private val loggedFailures: MutableSet<Any> = HashSet()
    private var nextPreviewId: Int = -1
    private var cachedLevel: ClientLevel? = null
    private var lastTickedGameTime: Long = Long.MIN_VALUE
    private val blockScenes: MutableMap<BlockState, BlockPreviewScene?> = HashMap()
    private val itemEntities: MutableMap<DeedPreview.Item, ItemEntity?> = HashMap()
    private val entities: MutableMap<EntityType<*>, Entity?> = HashMap()
    private val modelBounds: MutableMap<EntityType<*>, ModelBounds> = HashMap()

    /** Grid icon: flat item icons where they exist, static models otherwise. */
    fun renderIcon(graphics: GuiGraphicsExtractor, preview: DeedPreview, x0: Int, y0: Int, x1: Int, y1: Int) {
        when (preview) {
            is DeedPreview.Item -> renderFlatIcon(graphics, preview.stack, x0, y0, x1, y1)
            is DeedPreview.Block ->
                if (preview.state.block.asItem() != Items.AIR) {
                    renderFlatIcon(graphics, ItemStack(preview.state.block.asItem()), x0, y0, x1, y1)
                } else {
                    renderSafely(graphics, preview, x0, y0, x1, y1) { level ->
                        renderBlockScene(graphics, level, preview.state, x0, y0, x1, y1, BLOCK_ICON_YAW_DEGREES)
                    }
                }
            is DeedPreview.Entity -> renderSafely(graphics, preview, x0, y0, x1, y1) { level ->
                renderEntity(graphics, level, preview, x0, y0, x1, y1, ENTITY_ICON_YAW_DEGREES)
            }
            is DeedPreview.Biome -> renderBiome(graphics, preview, x0, y0, x1, y1)
            is DeedPreview.Structure -> renderStructure(graphics, preview, x0, y0, x1, y1)
            is DeedPreview.Effect -> renderEffect(graphics, preview, x0, y0, x1, y1)
        }
    }

    /** Focus / details window: spinning 3D models. */
    fun renderLive(graphics: GuiGraphicsExtractor, preview: DeedPreview, x0: Int, y0: Int, x1: Int, y1: Int) {
        when (preview) {
            is DeedPreview.Block -> renderSafely(graphics, preview, x0, y0, x1, y1) { level ->
                renderBlockScene(graphics, level, preview.state, x0, y0, x1, y1, 180f + seconds() * BLOCK_SPIN_DEGREES_PER_SECOND)
            }
            is DeedPreview.Item -> renderSafely(graphics, preview, x0, y0, x1, y1) { level ->
                renderItem(graphics, level, preview, x0, y0, x1, y1)
            }
            is DeedPreview.Entity -> renderSafely(graphics, preview, x0, y0, x1, y1) { level ->
                renderEntity(graphics, level, preview, x0, y0, x1, y1, 180f + seconds() * ENTITY_SPIN_DEGREES_PER_SECOND)
            }
            // No model: the same picture as in the grid.
            is DeedPreview.Biome, is DeedPreview.Structure, is DeedPreview.Effect -> renderIcon(graphics, preview, x0, y0, x1, y1)
        }
    }

    /** Small static model of one specific block state (variant lists). */
    fun renderStateIcon(graphics: GuiGraphicsExtractor, state: BlockState, x0: Int, y0: Int, x1: Int, y1: Int) {
        val preview = DeedPreview.Block(state, ItemStack(state.block.asItem()))
        renderSafely(graphics, preview, x0, y0, x1, y1) { level ->
            renderBlockScene(graphics, level, state, x0, y0, x1, y1, BLOCK_ICON_YAW_DEGREES)
        }
    }

    /** Drops every cached scene and preview entity (new level, resource reload). */
    fun clear() {
        cachedLevel = null
        lastTickedGameTime = Long.MIN_VALUE
        itemEntities.values.forEach { entity -> entity?.discard() }
        entities.values.forEach { entity -> entity?.discard() }
        blockScenes.clear()
        itemEntities.clear()
        entities.clear()
        modelBounds.clear()
    }

    // region Flat previews

    /** Inventory-style icon scaled up to fit the area (pixel art is scaled by whole steps). */
    private fun renderFlatIcon(graphics: GuiGraphicsExtractor, stack: ItemStack, x0: Int, y0: Int, x1: Int, y1: Int) {
        val size = min(x1 - x0, y1 - y0)
        val scale = max(1, size / 16).toFloat()
        val drawn = (16 * scale).toInt()
        graphics.pose().pushMatrix()
        graphics.pose().translate(((x0 + x1 - drawn) / 2).toFloat(), ((y0 + y1 - drawn) / 2).toFloat())
        graphics.pose().scale(scale, scale)
        graphics.item(stack, 0, 0)
        graphics.pose().popMatrix()
    }

    /** Swatch in the biome-map colour with a darker rim; the representative plant or block on top. */
    private fun renderBiome(graphics: GuiGraphicsExtractor, preview: DeedPreview.Biome, x0: Int, y0: Int, x1: Int, y1: Int) {
        graphics.fill(x0, y0, x1, y1, opaque(darker(preview.mapColor)))
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, opaque(preview.mapColor))
        if (!preview.fallbackIcon.isEmpty) {
            val inset = min(x1 - x0, y1 - y0) / 6
            renderFlatIcon(graphics, preview.fallbackIcon, x0 + inset, y0 + inset, x1 - inset, y1 - inset)
        }
    }

    /** Explorer-map look: a parchment square with the map marker scaled up in whole pixels. */
    private fun renderStructure(graphics: GuiGraphicsExtractor, preview: DeedPreview.Structure, x0: Int, y0: Int, x1: Int, y1: Int) {
        val icon = preview.mapIcon
        if (icon == null) {
            renderFlatIcon(graphics, preview.fallbackIcon, x0, y0, x1, y1)
            return
        }
        graphics.fill(x0, y0, x1, y1, MAP_BORDER)
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, MAP_PAPER)
        val scale = max(1, (min(x1 - x0, y1 - y0) - 4) / MAP_ICON_SIZE)
        val size = MAP_ICON_SIZE * scale
        val x = (x0 + x1 - size) / 2
        val y = (y0 + y1 - size) / 2
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0f, 0f, size, size, MAP_ICON_SIZE, MAP_ICON_SIZE, MAP_ICON_SIZE, MAP_ICON_SIZE)
    }

    /** The 18x18 HUD icon, scaled up in whole steps. */
    private fun renderEffect(graphics: GuiGraphicsExtractor, preview: DeedPreview.Effect, x0: Int, y0: Int, x1: Int, y1: Int) {
        val size = max(EFFECT_ICON_SIZE, (min(x1 - x0, y1 - y0) * 3 / 4) / EFFECT_ICON_SIZE * EFFECT_ICON_SIZE)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, preview.sprite, (x0 + x1 - size) / 2, (y0 + y1 - size) / 2, size, size)
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    /** The same colour at 60% brightness. */
    private fun darker(color: Int): Int {
        val r = (color shr 16 and 0xFF) * 3 / 5
        val g = (color shr 8 and 0xFF) * 3 / 5
        val b = (color and 0xFF) * 3 / 5
        return (r shl 16) or (g shl 8) or b
    }

    // endregion

    // region 3D previews

    private inline fun renderSafely(
        graphics: GuiGraphicsExtractor, preview: DeedPreview, x0: Int, y0: Int, x1: Int, y1: Int,
        render: (ClientLevel) -> Boolean
    ) {
        val level = Minecraft.getInstance().level
        val rendered = level != null && runCatching {
            prepareLevel(level)
            render(level)
        }.getOrElse { failure ->
            logOnce(preview, "Live preview failed for {}, falling back to its icon", failure)
            false
        }
        if (!rendered) renderFlatIcon(graphics, preview.fallbackIcon, x0, y0, x1, y1)
    }

    private fun prepareLevel(level: ClientLevel) {
        if (cachedLevel !== level) {
            clear()
            cachedLevel = level
        }
        // Animated block entities (the enchanting-table book) advance once per game tick.
        if (level.gameTime != lastTickedGameTime) {
            lastTickedGameTime = level.gameTime
            tickBlockEntities(level)
        }
    }

    private fun tickBlockEntities(level: ClientLevel) {
        for (scene in blockScenes.values) {
            scene?.parts?.forEach { part ->
                val blockEntity = part.blockEntity as? EnchantingTableBlockEntity ?: return@forEach
                EnchantingTableBlockEntity.bookAnimationTick(level, blockEntity.blockPos, blockEntity.blockState, blockEntity)
            }
        }
    }

    private fun renderBlockScene(
        graphics: GuiGraphicsExtractor, level: ClientLevel, state: BlockState,
        x0: Int, y0: Int, x1: Int, y1: Int, yawDegrees: Float
    ): Boolean {
        val scene = blockScenes.getOrPut(state) {
            runCatching { BlockPreviewScene.build(state, level) }
                .onFailure { failure -> logOnce(state, "Could not build block preview for {}", failure) }
                .getOrNull()
        } ?: return false

        // GUI space has Y pointing down, hence the Z flip; then the inventory tilt and the yaw.
        val rotation = Quaternionf()
            .rotateZ(Math.PI.toFloat())
            .rotateX(Math.toRadians(-BLOCK_TILT_DEGREES.toDouble()).toFloat())
            .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())

        val size = min(x1 - x0, y1 - y0)
        val pixelsPerBlock = size / (scene.extent * 1.75f)
        graphics.guiRenderState.addPicturesInPictureState(
            BlockPreviewRenderState(scene, rotation, x0, y0, x1, y1, pixelsPerBlock, graphics.scissorStack.peek())
        )
        return true
    }

    private fun renderItem(
        graphics: GuiGraphicsExtractor, level: ClientLevel, preview: DeedPreview.Item,
        x0: Int, y0: Int, x1: Int, y1: Int
    ): Boolean {
        val entity = itemEntities.getOrPut(preview) {
            runCatching { ItemEntity(level, 0.0, 0.0, 0.0, preview.stack.copy()).withPreviewId() }
                .onFailure { failure -> logOnce(preview, "Could not create preview item {}", failure) }
                .getOrNull()
        } ?: return false

        // A frozen age keeps the renderer's own bobbing still; the spin is applied here instead.
        entity.tickCount = 0
        val renderState = extract(entity)

        // Dropped block items are drawn at a quarter block, flat items at half a block: compensate so both fill the cell.
        val size = min(x1 - x0, y1 - y0)
        val scale = if (preview.stack.item is BlockItem) size * ITEM_BLOCK_SCALE else size * ITEM_FLAT_SCALE
        val rotation = Quaternionf()
            .rotateZ(Math.PI.toFloat())
            .rotateX(Math.toRadians(-ITEM_TILT_DEGREES.toDouble()).toFloat())
            .rotateY(Math.toRadians((seconds() * ITEM_SPIN_DEGREES_PER_SECOND).toDouble()).toFloat())
        graphics.entity(renderState, scale, Vector3f(0f, ITEM_CENTER_OFFSET, 0f), rotation, null, x0, y0, x1, y1)
        return true
    }

    private fun renderEntity(
        graphics: GuiGraphicsExtractor, level: ClientLevel, preview: DeedPreview.Entity,
        x0: Int, y0: Int, x1: Int, y1: Int, bodyYawDegrees: Float
    ): Boolean {
        val entity = entities.getOrPut(preview.type) {
            // ignoreChecks: hostile mobs refuse to be created on Peaceful, but a preview is not a spawn.
            runCatching { preview.type.create(level, EntitySpawnRequest(EntitySpawnReason.LOAD, true))?.withPreviewId() }
                .onFailure { failure -> logOnce(preview.type, "Could not create preview entity {}", failure) }
                .getOrNull()
                .also { entity -> if (entity == null) logOnce(preview.type, "Preview entity {} could not be created") }
        } ?: return false
        val renderState = extract(entity)

        if (renderState is LivingEntityRenderState) {
            renderState.bodyRot = bodyYawDegrees
            renderState.yRot = 0f
            renderState.xRot = 0f
            renderState.boundingBoxWidth /= renderState.scale
            renderState.boundingBoxHeight /= renderState.scale
            renderState.scale = 1f
        }

        val bounds = modelBounds.getOrPut(preview.type) { measureModel(entity, renderState) }
        val width = x1 - x0
        val height = y1 - y0

        // Tall mobs are shown as a bust (upper part, head near the top edge); small or wide mobs are shown
        // whole and centred. Both are framed on the model as drawn, which can reach past the hitbox.
        val bust = entity.bbHeight > BUST_MIN_HEIGHT && entity.bbHeight > entity.bbWidth
        val visibleHeight = max(if (bust) bounds.height * BUST_FRACTION else bounds.height, 0.1f)
        val scale = min(height * 0.9f / visibleHeight, width * 0.9f / max(bounds.radius * 2f, 0.1f))
        val translationY = if (bust) {
            bounds.maxY + (-height / 2f + height * 0.06f) / scale
        } else {
            (bounds.minY + bounds.maxY) / 2f
        }

        val rotation = Quaternionf().rotateZ(Math.PI.toFloat())
        val cameraTilt = Quaternionf().rotateX(Math.toRadians(-8.0).toFloat())
        graphics.entity(renderState, scale, Vector3f(0f, translationY, 0f), rotation, cameraTilt, x0, y0, x1, y1)
        return true
    }

    /**
     * Renderers use the entity id as a model seed, and an entity never added to a level has none.
     * Negative ids never collide with the level's real entities.
     */
    private fun <T : Entity> T.withPreviewId(): T = also { entity -> entity.id = nextPreviewId-- }

    /**
     * The model as it is drawn; a renderer that cannot be measured falls back to the hitbox, with room
     * for models longer than their hitbox as they turn.
     */
    private fun measureModel(entity: Entity, renderState: EntityRenderState): ModelBounds =
        runCatching { ModelBounds.measure(renderState) }
            .onFailure { failure -> logOnce("${entity.type} bounds", "Could not measure the preview model: {}", failure) }
            .getOrNull()
            ?: ModelBounds(0f, max(entity.bbHeight, 0.1f), max(entity.bbWidth, 0.1f) * WIDTH_ALLOWANCE / 2f)

    private fun extract(entity: Entity): EntityRenderState {
        val dispatcher = Minecraft.getInstance().entityRenderDispatcher
        val renderState = dispatcher.getRenderer(entity).createRenderState(entity, 1f)
        renderState.shadowPieces.clear()
        renderState.outlineColor = 0
        // A boat's water patch keeps the water out of it in the world. The GUI has no water, and under
        // Fabulous graphics the patch's pipeline (depth only, no colour) cannot draw into a GUI picture at all.
        (renderState as? BoatRenderState)?.isUnderWater = true
        return renderState
    }

    // endregion

    private fun logOnce(key: Any, message: String, failure: Throwable? = null) {
        if (loggedFailures.add(key)) EveryDeeds.LOGGER.warn(message, key, failure)
    }

    private fun seconds(): Float = (Util.getMillis() % 3_600_000L) / 1000f
}
