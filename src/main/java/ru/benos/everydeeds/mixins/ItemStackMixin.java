package ru.benos.everydeeds.mixins;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/**
 * One hook for every result slot: crafting grid, furnaces, smithing table, stonecutter, loom and
 * merchants all call {@link ItemStack#onCraftedBy} on the taken result.
 */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
	@Inject(method = "onCraftedBy", at = @At("HEAD"))
	private void everydeeds$onCrafted(Player player, int craftCount, CallbackInfo callback) {
		DeedHooks.onItemCrafted(player, (ItemStack) (Object) this, craftCount);
	}
}
