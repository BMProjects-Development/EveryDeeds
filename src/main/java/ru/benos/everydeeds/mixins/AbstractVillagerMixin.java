package ru.benos.everydeeds.mixins;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** A completed trade, for villagers and wandering traders alike. */
@Mixin(AbstractVillager.class)
abstract class AbstractVillagerMixin {
	@Inject(method = "notifyTrade", at = @At("HEAD"))
	private void everydeeds$onTrade(MerchantOffer offer, CallbackInfo callback) {
		AbstractVillager self = (AbstractVillager) (Object) this;
		DeedHooks.onTraded(self.getTradingPlayer(), self);
	}
}
