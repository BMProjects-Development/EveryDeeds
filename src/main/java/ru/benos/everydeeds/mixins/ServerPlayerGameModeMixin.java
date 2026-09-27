package ru.benos.everydeeds.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.benos.everydeeds.tracking.DeedHooks;

/**
 * A block counts as used when it reacted to the right click: its own item interaction (filling a
 * cauldron, a book on a lectern) or its empty-hand interaction (a lever, a door, a crafting table).
 * An item acting on a block (placing, tilling) is the item being used, not the block.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
	@Inject(
		method = "useItemOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/advancements/triggers/ItemUsedOnLocationTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemInstance;)V",
			ordinal = 0
		)
	)
	private void everydeeds$onBlockUsedWithItem(
		ServerPlayer player, Level level, ItemStack itemStack, InteractionHand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> callback, @Local BlockState state
	) {
		DeedHooks.onBlockUsed(player, state);
	}

	@Inject(
		method = "useItemOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/advancements/triggers/DefaultBlockInteractionTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;)V"
		)
	)
	private void everydeeds$onBlockUsed(
		ServerPlayer player, Level level, ItemStack itemStack, InteractionHand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> callback, @Local BlockState state
	) {
		DeedHooks.onBlockUsed(player, state);
	}
}
