package ru.benos.everydeeds.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Block placement has no Fabric event: record the final placed state right before the item is consumed. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
	@Inject(
		method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V")
	)
	private void everydeeds$onPlaced(
		BlockPlaceContext placeContext,
		CallbackInfoReturnable<InteractionResult> callback,
		@Local Player player,
		@Local(ordinal = 1) BlockState placedState
	) {
		DeedHooks.onBlockPlaced(player, placedState);
	}
}
