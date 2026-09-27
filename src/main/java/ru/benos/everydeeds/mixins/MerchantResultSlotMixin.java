package ru.benos.everydeeds.mixins;

import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Marks the merchant result hand-out so the bought item counts as obtained but not as crafted. */
@Mixin(MerchantResultSlot.class)
abstract class MerchantResultSlotMixin {
	@Inject(method = "checkTakeAchievements", at = @At("HEAD"))
	private void everydeeds$beginTrade(ItemStack carried, CallbackInfo callback) {
		DeedHooks.setTradeInProgress(true);
	}

	@Inject(method = "checkTakeAchievements", at = @At("RETURN"))
	private void everydeeds$endTrade(ItemStack carried, CallbackInfo callback) {
		DeedHooks.setTradeInProgress(false);
	}
}
