package ru.benos.everydeeds.mixins;

import net.minecraft.advancements.triggers.TameAnimalTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Every way of taming (bones, fish, seeds, riding a horse until it accepts you) ends in this trigger. */
@Mixin(TameAnimalTrigger.class)
abstract class TameAnimalTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void everydeeds$onTamed(ServerPlayer player, Animal animal, CallbackInfo callback) {
		DeedHooks.onTamed(player, animal);
	}
}
