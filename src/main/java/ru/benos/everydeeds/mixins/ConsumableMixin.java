package ru.benos.everydeeds.mixins;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Every finished consumable (food, potions, milk) passes here, with the stack before it is shrunk. */
@Mixin(Consumable.class)
abstract class ConsumableMixin {
	@Inject(method = "onConsume", at = @At("HEAD"))
	private void everydeeds$onConsume(Level level, LivingEntity user, ItemStack stack, CallbackInfoReturnable<ItemStack> callback) {
		DeedHooks.onConsumed(user, stack);
	}
}
