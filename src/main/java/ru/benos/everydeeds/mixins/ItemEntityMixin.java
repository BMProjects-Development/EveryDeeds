package ru.benos.everydeeds.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Item pickup: fires after the inventory accepted the stack, with the stack's components intact. */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
	// The inventory reports the new stack while it is being added; the pickup below counts it with its real amount.
	@Inject(method = "playerTouch", at = @At("HEAD"))
	private void everydeeds$beginPickup(Player player, CallbackInfo callback) {
		DeedHooks.setPickupInProgress(true);
	}

	@Inject(method = "playerTouch", at = @At("RETURN"))
	private void everydeeds$endPickup(Player player, CallbackInfo callback) {
		DeedHooks.setPickupInProgress(false);
	}

	@Inject(
		method = "playerTouch",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onItemPickup(Lnet/minecraft/world/entity/item/ItemEntity;)V")
	)
	private void everydeeds$onPickedUp(Player player, CallbackInfo callback, @Local ItemStack itemStack, @Local(ordinal = 0) int orgCount) {
		ItemEntity self = (ItemEntity) (Object) this;
		// A fully picked-up entity is discarded and its stack count restored; a partial pickup leaves the remainder.
		int picked = self.isRemoved() ? orgCount : orgCount - itemStack.getCount();
		DeedHooks.onItemPickedUp(player, itemStack, picked);
	}
}
