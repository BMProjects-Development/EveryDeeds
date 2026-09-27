package ru.benos.everydeeds.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** A status effect newly applied to the player: potions, food, beacons, mobs, blocks all end here. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerEffectMixin {
	@Inject(method = "onEffectAdded", at = @At("HEAD"))
	private void everydeeds$onEffectAdded(MobEffectInstance effect, Entity source, CallbackInfo callback) {
		DeedHooks.onEffectAdded((ServerPlayer) (Object) this, effect.getEffect(), effect.getAmplifier());
	}
}
