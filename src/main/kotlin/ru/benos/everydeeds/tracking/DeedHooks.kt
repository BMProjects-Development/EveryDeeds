package ru.benos.everydeeds.tracking

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.LavaCauldronBlock
import net.minecraft.world.level.block.MagmaBlock
import net.minecraft.world.level.block.PowderSnowBlock
import net.minecraft.world.level.block.SpeleothemBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import ru.benos.everydeeds.deed.DeedAction
import ru.benos.everydeeds.deed.DeedSubject

/**
 * Loader-neutral signal sinks. Mixins (Fabric) or native events (NeoForge) call these; they only
 * translate "something happened" into [DeedTracker.record] with a proper [DeedSubject].
 * Stacks are copied because the game keeps mutating or consuming them after the hook.
 */
object DeedHooks {
    /**
     * Set while a merchant result slot hands out its item: trades also go through [ItemStack.onCraftedBy],
     * but a bought item was not crafted. The server thread is the only caller, so a plain flag suffices.
     */
    @JvmStatic
    var tradeInProgress: Boolean = false

    /** Set during an item pickup: the inventory change it causes is counted by [onItemPickedUp], not as mere presence. */
    @JvmStatic
    var pickupInProgress: Boolean = false

    // region Blocks

    @JvmStatic
    fun onBlockBroken(player: Player, state: BlockState) {
        val serverPlayer = player as? ServerPlayer ?: return
        DeedTracker.record(serverPlayer, DeedAction.BROKEN, DeedSubject.Block(state))
        if (isMatureCrop(state)) {
            DeedTracker.record(serverPlayer, DeedAction.GROWN, DeedSubject.Block(state))
        }
    }

    @JvmStatic
    fun onBlockPlaced(player: Player?, placedState: BlockState) {
        val serverPlayer = player as? ServerPlayer ?: return
        DeedTracker.record(serverPlayer, DeedAction.PLACED, DeedSubject.Block(placedState))
    }

    /** A right click the block reacted to (the game reported it as consumed). [state] is the state before the click. */
    @JvmStatic
    fun onBlockUsed(player: ServerPlayer, state: BlockState) {
        DeedTracker.record(player, DeedAction.USED, DeedSubject.Block(state))
    }

    /** Bone meal took effect on [stateBefore]. */
    @JvmStatic
    fun onBlockBonemealed(player: Player?, stateBefore: BlockState) {
        val serverPlayer = player as? ServerPlayer ?: return
        DeedTracker.record(serverPlayer, DeedAction.GROWN, DeedSubject.Block(stateBefore))
    }

    /** Crops and similar plants keep their growth in an "age" property; the last age is the harvestable one. */
    private fun isMatureCrop(state: BlockState): Boolean {
        val age = state.properties.firstOrNull { property -> property.name == "age" } as? IntegerProperty ?: return false
        return state.getValue(age) == age.possibleValues.max()
    }

    // endregion

    // region Items

    @JvmStatic
    fun onItemPickedUp(player: Player, stack: ItemStack, amount: Int) {
        val serverPlayer = player as? ServerPlayer ?: return
        if (stack.isEmpty) return
        DeedTracker.record(serverPlayer, DeedAction.OBTAINED, DeedSubject.Item(stack.copyWithCount(1)), amount)
    }

    /**
     * Result taken from a crafting grid, furnace, stonecutter, smithing table, loom or merchant. Everything
     * counts as obtained; everything except trades also counts as crafted, for the item and for the block it places.
     */
    @JvmStatic
    fun onItemCrafted(player: Player, stack: ItemStack, amount: Int) {
        val serverPlayer = player as? ServerPlayer ?: return
        if (stack.isEmpty) return
        val count = amount.coerceAtLeast(1)
        val item = DeedSubject.Item(stack.copyWithCount(1))
        DeedTracker.record(serverPlayer, DeedAction.OBTAINED, item, count)
        if (tradeInProgress) return

        DeedTracker.record(serverPlayer, DeedAction.CRAFTED, item, count)
        val blockItem = stack.item as? BlockItem ?: return
        DeedTracker.record(serverPlayer, DeedAction.CRAFTED, DeedSubject.Block(blockItem.block.defaultBlockState()), count)
    }

    /** Any change of a player inventory slot: whatever lands there has been obtained, however it got there. */
    @JvmStatic
    fun onInventorySlotChanged(player: ServerPlayer, stack: ItemStack) {
        if (stack.isEmpty || pickupInProgress) return
        DeedTracker.recordPresence(player, DeedAction.OBTAINED, DeedSubject.Item(stack.copyWithCount(1)))
    }

    /** Items the player already carries when joining (inventories from before the mod, or from another set). */
    @JvmStatic
    fun onPlayerJoined(player: ServerPlayer) {
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            onInventorySlotChanged(player, inventory.getItem(slot))
        }
    }

    /** The vanilla "used" statistic: swings, shots, eaten food, tools breaking blocks. Placing blocks is tracked as placed. */
    @JvmStatic
    fun onItemUsed(player: ServerPlayer, item: Item) {
        if (item is BlockItem) return
        DeedTracker.record(player, DeedAction.USED, DeedSubject.Item(ItemStack(item)))
    }

    /** A consumable finished; only food counts as eaten (potions and milk are drunk, not eaten). */
    @JvmStatic
    fun onConsumed(user: LivingEntity, stack: ItemStack) {
        val serverPlayer = user as? ServerPlayer ?: return
        if (stack.isEmpty || !stack.has(DataComponents.FOOD)) return
        DeedTracker.record(serverPlayer, DeedAction.EATEN, DeedSubject.Item(stack.copyWithCount(1)))
    }

    @JvmStatic
    fun onFished(player: ServerPlayer, items: Collection<ItemStack>) {
        items.filterNot(ItemStack::isEmpty).forEach { stack ->
            DeedTracker.record(player, DeedAction.FISHED, DeedSubject.Item(stack.copyWithCount(1)), stack.count)
        }
    }

    @JvmStatic
    fun onBrewedTaken(player: Player, stack: ItemStack) {
        val serverPlayer = player as? ServerPlayer ?: return
        if (stack.isEmpty) return
        DeedTracker.record(serverPlayer, DeedAction.BREWED, DeedSubject.Item(stack.copyWithCount(1)), stack.count)
    }

    @JvmStatic
    fun onEnchanted(player: ServerPlayer, result: ItemStack) {
        if (result.isEmpty) return
        DeedTracker.record(player, DeedAction.ENCHANTED, DeedSubject.Item(result.copyWithCount(1)))
    }

    // endregion

    // region Entities

    @JvmStatic
    fun onEntityKilled(killer: Entity?, victim: LivingEntity) {
        val serverPlayer = killer as? ServerPlayer ?: return
        DeedTracker.record(serverPlayer, DeedAction.KILLED, DeedSubject.Entity(victim))
    }

    /** Damage the player took: from a mob (or its projectile), from a falling block, or from a block they touch. */
    @JvmStatic
    fun onPlayerDamaged(player: LivingEntity, source: DamageSource) {
        val serverPlayer = player as? ServerPlayer ?: return
        val fallingBlock = source.directEntity as? FallingBlockEntity
        val attacker = source.entity
        when {
            fallingBlock != null -> DeedTracker.record(serverPlayer, DeedAction.DAMAGED_BY, DeedSubject.Block(fallingBlock.blockState))
            attacker != null -> if (attacker !is Player) DeedTracker.record(serverPlayer, DeedAction.DAMAGED_BY, DeedSubject.Entity(attacker))
            else -> touchedBlock(serverPlayer, source)?.let { state ->
                DeedTracker.record(serverPlayer, DeedAction.DAMAGED_BY, DeedSubject.Block(state))
            }
        }
    }

    /**
     * Blocks that hurt by touch, by the damage type they deal. Such damage carries no position, so the
     * block is the one of that kind among the blocks the player touches or stands on.
     */
    private val BLOCK_DAMAGE: Map<ResourceKey<DamageType>, (BlockState) -> Boolean> = mapOf(
        DamageTypes.CACTUS to { state -> state.block is CactusBlock },
        DamageTypes.SWEET_BERRY_BUSH to { state -> state.block is SweetBerryBushBlock },
        DamageTypes.HOT_FLOOR to { state -> state.block is MagmaBlock },
        DamageTypes.IN_FIRE to { state -> state.block is BaseFireBlock },
        DamageTypes.CAMPFIRE to { state -> state.block is CampfireBlock },
        DamageTypes.LAVA to { state -> state.fluidState.`is`(FluidTags.LAVA) || state.block is LavaCauldronBlock },
        DamageTypes.FREEZE to { state -> state.block is PowderSnowBlock },
        DamageTypes.STALAGMITE to { state -> state.block is SpeleothemBlock }
    )

    /** How far around the player's hitbox a block still counts as touched (the floor, a cactus beside them). */
    private const val TOUCH_MARGIN = 0.1

    private fun touchedBlock(player: ServerPlayer, source: DamageSource): BlockState? {
        val dealtBy = BLOCK_DAMAGE.entries.firstOrNull { (type, _) -> source.`is`(type) }?.value ?: return null
        val level = player.level()
        return BlockPos.betweenClosed(player.boundingBox.inflate(TOUCH_MARGIN)).asSequence()
            .map { pos -> level.getBlockState(pos) }
            .firstOrNull(dealtBy)
    }

    @JvmStatic
    fun onTraded(player: Player?, merchant: Entity) {
        val serverPlayer = player as? ServerPlayer ?: return
        DeedTracker.record(serverPlayer, DeedAction.TRADED, DeedSubject.Entity(merchant))
    }

    @JvmStatic
    fun onTamed(player: ServerPlayer, animal: Entity) {
        DeedTracker.record(player, DeedAction.TAMED, DeedSubject.Entity(animal))
    }

    /** Counted for the baby's type (a mule for horse + donkey); the parent's if the game made no baby entity. */
    @JvmStatic
    fun onBred(player: ServerPlayer, parent: Entity, child: Entity?) {
        DeedTracker.record(player, DeedAction.BRED, DeedSubject.Entity(child ?: parent))
    }

    @JvmStatic
    fun onMounted(player: ServerPlayer, vehicle: Entity) {
        DeedTracker.record(player, DeedAction.RIDDEN, DeedSubject.Entity(vehicle))
    }

    // endregion

    // region Effects

    /** A status effect newly applied to the player (not a refresh of one it already has). */
    @JvmStatic
    fun onEffectAdded(player: ServerPlayer, effect: Holder<MobEffect>, amplifier: Int) {
        if (!effect.unwrapKey().isPresent) return
        DeedTracker.record(player, DeedAction.OBTAINED, DeedSubject.Effect(effect, amplifier + 1))
    }

    // endregion
}
