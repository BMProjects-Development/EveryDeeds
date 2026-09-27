package ru.benos.everydeeds.mixins;

import net.minecraft.advancements.triggers.LootTableTrigger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.StructureTracker;

/**
 * Chests, barrels, chest boats and minecarts, and brushed suspicious blocks all fire this trigger when a
 * player makes them roll their loot: one hook for every way of looting a structure.
 */
@Mixin(LootTableTrigger.class)
abstract class LootTableTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void everydeeds$onLoot(ServerPlayer player, ResourceKey<LootTable> lootTable, CallbackInfo callback) {
		StructureTracker.onLootGenerated(player);
	}
}
