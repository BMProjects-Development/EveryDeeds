package ru.benos.everydeeds.client.gui.preview

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.model.Model
import net.minecraft.client.renderer.OrderedSubmitNodeCollector
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.UvMapping
import net.minecraft.client.resources.model.geometry.ItemQuads
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where an entity's model really is, in blocks around its feet: from [minY] to [maxY], and at most
 * [radius] away from its vertical axis, whichever way it turns. Hitboxes are made for gameplay and
 * often differ from the model: a chicken's head sticks out above its hitbox.
 */
class ModelBounds(val minY: Float, val maxY: Float, val radius: Float) {
    val height: Float get() = maxY - minY

    companion object {
        /**
         * Measures [renderState] by a dry run of its renderer. The renderer submits its models as usual
         * (with its own scale, and its layers: armour, saddles, a sheep's wool) to a collector that
         * draws nothing and only records where the vertices would be. Null when nothing was submitted.
         */
        fun measure(renderState: EntityRenderState): ModelBounds? {
            val collector = MeasuringCollector()
            Minecraft.getInstance().entityRenderDispatcher.submit(renderState, CameraRenderState(), 0.0, 0.0, 0.0, PoseStack(), collector)
            return collector.bounds()
        }
    }
}

/** Records the vertices of submitted models and blocks. Shadows, names, items, flames and particles are not part of the model. */
private class MeasuringCollector : SubmitNodeCollector {
    private var minY = Float.POSITIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var radius = 0f

    fun bounds(): ModelBounds? = if (minY > maxY) null else ModelBounds(minY, maxY, radius)

    private fun include(point: Vector3fc) {
        minY = min(minY, point.y())
        maxY = max(maxY, point.y())
        radius = max(radius, sqrt(point.x() * point.x() + point.z() * point.z()))
    }

    /** A block model fills (at most) its unit cube. */
    private fun includeBlock(poseStack: PoseStack) {
        val pose = poseStack.last().pose()
        for (x in 0..1) for (y in 0..1) for (z in 0..1) {
            include(pose.transformPosition(x.toFloat(), y.toFloat(), z.toFloat(), Vector3f()))
        }
    }

    override fun order(order: Int): OrderedSubmitNodeCollector = this

    override fun <S : Any> submitModel(
        model: Model<in S>, state: S, poseStack: PoseStack, renderType: RenderType,
        lightCoords: Int, overlayCoords: Int, tintedColor: Int, uvMapping: UvMapping?, outlineColor: Int
    ) {
        // Posed the way the model feature renderer poses it right before drawing.
        model.setupAnim(state)
        model.root().getExtentsForGui(poseStack) { point -> include(point) }
    }

    override fun submitBlockModel(
        poseStack: PoseStack, renderType: RenderType, parts: List<BlockStateModelPart>, tintLayers: IntArray,
        lightCoords: Int, overlayCoords: Int, outlineColor: Int
    ) = includeBlock(poseStack)

    override fun submitMovingBlock(poseStack: PoseStack, movingBlockRenderState: MovingBlockRenderState, outlineColor: Int) =
        includeBlock(poseStack)

    override fun submitShadow(poseStack: PoseStack, radius: Float, pieces: List<EntityRenderState.ShadowPiece>) {}

    override fun submitNameTag(
        poseStack: PoseStack, nameTagAttachment: Vec3?, offset: Int, name: Component, seeThrough: Boolean,
        lightCoords: Int, camera: CameraRenderState
    ) {}

    override fun submitText(
        poseStack: PoseStack, x: Float, y: Float, string: FormattedCharSequence, dropShadow: Boolean, displayMode: Font.DisplayMode,
        lightCoords: Int, color: Int, backgroundColor: Int, outlineColor: Int
    ) {}

    override fun submitTextBackground(
        poseStack: PoseStack, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, displayMode: Font.DisplayMode, lightCoords: Int
    ) {}

    override fun submitFlame(poseStack: PoseStack, renderState: EntityRenderState, rotation: Quaternionf) {}

    override fun submitLeash(poseStack: PoseStack, leashState: EntityRenderState.LeashState) {}

    override fun <S : Any> submitCrumblingOverlay(
        model: Model<in S>, state: S, poseStack: PoseStack, renderType: RenderType, lightCoords: Int, overlayCoords: Int,
        tintedColor: Int, crumblingOverlay: ModelFeatureRenderer.CrumblingOverlay
    ) {}

    override fun submitBreakingBlockModel(poseStack: PoseStack, parts: List<BlockStateModelPart>, progress: Int, isBlockTranslucent: Boolean) {}

    override fun submitShapeOutline(poseStack: PoseStack, shape: VoxelShape, renderType: RenderType, color: Int, width: Float, afterTerrain: Boolean) {}

    override fun submitItem(
        poseStack: PoseStack, displayContext: ItemDisplayContext, lightCoords: Int, overlayCoords: Int, outlineColor: Int,
        tintLayers: IntArray, quads: ItemQuads, foilType: ItemStackRenderState.FoilType
    ) {}

    override fun submitCustomGeometry(poseStack: PoseStack, renderType: RenderType, customGeometryRenderer: SubmitNodeCollector.CustomGeometryRenderer) {}

    override fun submitQuadParticleGroup(particles: QuadParticleRenderState) {}

    override fun submitGizmoPrimitives(group: DrawableGizmoPrimitives.Group, camera: CameraRenderState, onTop: Boolean) {}
}
