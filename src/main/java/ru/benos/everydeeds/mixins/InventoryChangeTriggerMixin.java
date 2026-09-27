package ru.benos.everydeeds.mixins;

import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Fired by the game for every change of a player inventory slot: no per-tick inventory scanning needed. */
@Mixin(InventoryChangeTrigger.class)
abstract class InventoryChangeTriggerMixin {
	@Inject(
		method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/ItemStack;)V",
		at = @At("HEAD")
	)
	private void everydeeds$onSlotChanged(ServerPlayer player, Inventory inventory, ItemStack changedItem, CallbackInfo callback) {
		DeedHooks.onInventorySlotChanged(player, changedItem);
	}
}
