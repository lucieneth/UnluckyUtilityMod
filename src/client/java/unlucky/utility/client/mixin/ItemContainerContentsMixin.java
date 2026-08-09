package unlucky.utility.client.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.module.modules.misc.InventoryInfo;

/**
 * When InventoryInfo's container preview is on, drop the vanilla "x N ItemName"
 * text lines the {@code CONTAINER} component would add — our grid image replaces
 * them, so this keeps the tooltip from showing both.
 */
@Mixin(ItemContainerContents.class)
public class ItemContainerContentsMixin {
	@Redirect(method = "createStackFromSlot", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStackTemplate;create()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack unluckyCreateOversizedContent(ItemStackTemplate template) {
		ItemStack raw = new ItemStack(template.item(), template.count(), template.components());
		if (raw.getCount() > raw.getMaxStackSize()
				&& ItemStack.validateStrict(raw.copyWithCount(raw.getMaxStackSize())).result().isPresent()) {
			return raw;
		}
		return template.create();
	}

	@Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
	private void unlucky$suppressText(CallbackInfo ci) {
		if (InventoryInfo.showContainerGrid()) {
			ci.cancel();
		}
	}
}
