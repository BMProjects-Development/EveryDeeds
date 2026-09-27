package ru.benos.everydeeds.mixins;

import net.minecraft.advancements.triggers.EnchantedItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Enchanting table results, with their fresh enchantments. */
@Mixin(EnchantedItemTrigger.class)
abstract class EnchantedItemTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void everydeeds$onEnchanted(ServerPlayer player, ItemStack itemStack, int levels, CallbackInfo callback) {
		DeedHooks.onEnchanted(player, itemStack);
	}
}
