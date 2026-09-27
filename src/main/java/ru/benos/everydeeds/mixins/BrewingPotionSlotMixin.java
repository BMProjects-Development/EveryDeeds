package ru.benos.everydeeds.mixins;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** A potion taken out of a brewing stand's bottle slot (where the vanilla "brewed potion" advancement fires). */
@Mixin(targets = "net.minecraft.world.inventory.BrewingStandMenu$PotionSlot")
abstract class BrewingPotionSlotMixin {
	@Inject(method = "onTake", at = @At("HEAD"))
	private void everydeeds$onTaken(Player player, ItemStack carried, CallbackInfo callback) {
		DeedHooks.onBrewedTaken(player, carried);
	}
}
