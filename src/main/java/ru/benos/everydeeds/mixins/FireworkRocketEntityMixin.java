package ru.benos.everydeeds.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.Celebration;

/** Celebration rockets still burst in the sky, but their explosion damages nobody. */
@Mixin(FireworkRocketEntity.class)
abstract class FireworkRocketEntityMixin {
	@Inject(method = "dealExplosionDamage", at = @At("HEAD"), cancellable = true)
	private void everydeeds$skipCelebrationDamage(ServerLevel level, CallbackInfo callback) {
		if (Celebration.isHarmless((FireworkRocketEntity) (Object) this)) callback.cancel();
	}
}
