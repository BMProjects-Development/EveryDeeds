package ru.benos.everydeeds.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.benos.everydeeds.tracking.DeedHooks;

/** Bone meal applied by a player that took effect: forced growth. The state is taken before it grows (a sapling, not the tree). */
@Mixin(BoneMealItem.class)
abstract class BoneMealItemMixin {
	@WrapOperation(
		method = "useOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/BoneMealItem;growCrop(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
		)
	)
	private boolean everydeeds$trackGrowth(
		ItemStack stack, Level level, BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) UseOnContext context
	) {
		BlockState before = level.getBlockState(pos);
		boolean grown = original.call(stack, level, pos);
		if (grown && !level.isClientSide()) {
			DeedHooks.onBlockBonemealed(context.getPlayer(), before);
		}
		return grown;
	}
}
