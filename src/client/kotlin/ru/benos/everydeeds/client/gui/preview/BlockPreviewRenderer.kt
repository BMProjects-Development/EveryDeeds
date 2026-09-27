package ru.benos.everydeeds.client.gui.preview

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.entity.DisplayRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionfc

/** One block scene drawn into the cell `[x0, x1) x [y0, y1)`. */
class BlockPreviewRenderState(
    val scene: BlockPreviewScene,
    val rotation: Quaternionfc,
    private val x0: Int,
    private val y0: Int,
    private val x1: Int,
    private val y1: Int,
    private val scale: Float,
    private val scissorArea: ScreenRectangle?
) : PictureInPictureRenderState {
    private val bounds: ScreenRectangle? = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea)

    override fun x0(): Int = x0
    override fun y0(): Int = y0
    override fun x1(): Int = x1
    override fun y1(): Int = y1
    override fun scale(): Float = scale
    override fun scissorArea(): ScreenRectangle? = scissorArea
    override fun bounds(): ScreenRectangle? = bounds
}

/**
 * Picture-in-picture renderer for [BlockPreviewScene]s. Block models, block entities and fluids are
 * submitted into the same texture, so they share a depth buffer and occlude each other correctly
 * while the scene spins (unlike stacking one entity preview per part).
 */
class BlockPreviewRenderer : PictureInPictureRenderer<BlockPreviewRenderState>() {
    private val minecraft: Minecraft = Minecraft.getInstance()
    // Created on first use: the renderer itself is constructed while the GUI renderer is still being set up.
    private val modelResolver: BlockModelResolver by lazy { BlockModelResolver(minecraft.modelManager) }

    override fun getRenderStateClass(): Class<BlockPreviewRenderState> = BlockPreviewRenderState::class.java

    override fun getTextureLabel(): String = "everydeeds_block_preview"

    override fun getTranslateY(height: Int, guiScale: Int): Float = height / 2f

    override fun renderToTexture(renderState: BlockPreviewRenderState, poseStack: PoseStack, submitNodeCollector: SubmitNodeCollector) {
        val scene = renderState.scene
        minecraft.gameRenderer.lighting().setupFor(if (scene.flatLighting) Lighting.Entry.ITEMS_FLAT else Lighting.Entry.ITEMS_3D)

        poseStack.pushPose()
        poseStack.rotate(renderState.rotation)
        poseStack.translate(-scene.center.x, -scene.center.y, -scene.center.z)

        for (part in scene.parts) {
            poseStack.pushPose()
            poseStack.translate(part.offset.x.toFloat(), part.offset.y.toFloat(), part.offset.z.toFloat())

            if (part.state.renderShape != RenderShape.INVISIBLE) {
                val model = part.model ?: BlockModelRenderState().also { resolved ->
                    modelResolver.update(resolved, part.state, DisplayRenderer.BLOCK_DISPLAY_CONTEXT)
                    part.model = resolved
                }
                model.submit(poseStack, submitNodeCollector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)
            }
            part.blockEntity?.let { blockEntity -> submitBlockEntity(blockEntity, poseStack, submitNodeCollector) }
            poseStack.popPose()
        }

        scene.fluid?.let { fluid -> submitFluid(fluid, poseStack, submitNodeCollector) }
        poseStack.popPose()
    }

    /** Block entities are extracted directly: the dispatcher's distance and level checks do not apply to a preview. */
    private fun submitBlockEntity(blockEntity: BlockEntity, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val dispatcher = minecraft.blockEntityRenderDispatcher
        val renderer = dispatcher.getRenderer<BlockEntity, BlockEntityRenderState>(blockEntity) ?: return
        val state = renderer.createRenderState()
        renderer.extractRenderState(blockEntity, state, minecraft.deltaTracker.getGameTimeDeltaPartialTick(false), Vec3.ZERO, null)
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT
        dispatcher.submit(state, poseStack, collector, CameraRenderState())
    }

    /**
     * Fluids have no block model (the level renders them procedurally), so a source block is drawn as a
     * slightly lowered cube using the fluid's own still texture and water tint.
     */
    private fun submitFluid(fluid: FluidState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val model = minecraft.modelManager.fluidStateModelSet.get(fluid)
        val sprite = model.stillMaterial().sprite()
        val color = if (fluid.type.isSame(Fluids.WATER)) WATER_COLOR else OPAQUE_WHITE

        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(sprite.atlasLocation())) { pose, buffer ->
            val top = FLUID_HEIGHT
            val face = FluidFace(buffer, pose, sprite, color)
            face.draw(0f, 1f, 0f, Corner(0f, top, 0f, 0f, 0f), Corner(0f, top, 1f, 0f, 1f), Corner(1f, top, 1f, 1f, 1f), Corner(1f, top, 0f, 1f, 0f))
            face.draw(0f, -1f, 0f, Corner(0f, 0f, 0f, 0f, 0f), Corner(1f, 0f, 0f, 1f, 0f), Corner(1f, 0f, 1f, 1f, 1f), Corner(0f, 0f, 1f, 0f, 1f))
            face.side(0f, 0f, 1f, 0f, top, 0f, 0f, -1f)
            face.side(1f, 1f, 0f, 1f, top, 0f, 0f, 1f)
            face.side(0f, 1f, 0f, 0f, top, -1f, 0f, 0f)
            face.side(1f, 0f, 1f, 1f, top, 1f, 0f, 0f)
        }
    }

    private data class Corner(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

    /** Emits textured quads of the fluid cube in the entity vertex format. */
    private class FluidFace(
        private val buffer: VertexConsumer,
        private val pose: PoseStack.Pose,
        private val sprite: TextureAtlasSprite,
        private val color: Int
    ) {
        fun draw(nx: Float, ny: Float, nz: Float, vararg corners: Corner) {
            for (corner in corners) {
                buffer.addVertex(pose, corner.x, corner.y, corner.z)
                    .setColor(color)
                    .setUv(sprite.getU(corner.u), sprite.getV(corner.v))
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT)
                    .setNormal(pose, nx, ny, nz)
            }
        }

        /** Vertical face from (xa, za) to (xb, zb), from the bottom to [top]; the texture is cropped to the height. */
        fun side(xa: Float, xb: Float, za: Float, zb: Float, top: Float, nx: Float, ny: Float, nz: Float) {
            draw(
                nx, ny, nz,
                Corner(xa, top, za, 0f, 1f - top), Corner(xa, 0f, za, 0f, 1f),
                Corner(xb, 0f, zb, 1f, 1f), Corner(xb, top, zb, 1f, 1f - top)
            )
        }
    }

    private companion object {
        const val FLUID_HEIGHT = 14f / 16f
        const val WATER_COLOR = 0xD03F76E4.toInt()
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    }
}
