package ru.benos.everydeeds.mixins;

import net.minecraft.advancements.triggers.BredAnimalsTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Breeding by the player (the one who fed the parents). */
@Mixin(BredAnimalsTrigger.class)
abstract class BredAnimalsTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void everydeeds$onBred(ServerPlayer player, Animal parent, Animal partner, AgeableMob child, CallbackInfo callback) {
		DeedHooks.onBred(player, parent, child);
	}
}
