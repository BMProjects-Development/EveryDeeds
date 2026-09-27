package ru.benos.everydeeds.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.benos.everydeeds.tracking.DeedHooks;

/** The vanilla "item used" statistic already covers every way of using an item: one hook instead of one per item. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"))
	private void everydeeds$onStat(Stat<?> stat, int count, CallbackInfo callback) {
		if (stat.getType() == Stats.ITEM_USED && stat.getValue() instanceof Item item) {
			DeedHooks.onItemUsed((ServerPlayer) (Object) this, item);
		}
	}
}
