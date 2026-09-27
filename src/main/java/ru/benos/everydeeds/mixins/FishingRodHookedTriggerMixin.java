package ru.benos.everydeeds.mixins;

import java.util.Collection;
import net.minecraft.advancements.triggers.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Fishing loot, fired with the caught items before they fly to the player. */
@Mixin(FishingRodHookedTrigger.class)
abstract class FishingRodHookedTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void everydeeds$onFished(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items, CallbackInfo callback) {
		DeedHooks.onFished(player, items);
	}
}
