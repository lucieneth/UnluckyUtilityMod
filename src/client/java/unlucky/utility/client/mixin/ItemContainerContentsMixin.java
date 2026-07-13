package unlucky.utility.client.mixin;

import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.module.modules.misc.InventoryInfo;

/**
 * When InventoryInfo's container preview is on, drop the vanilla "x N ItemName"
 * text lines the {@code CONTAINER} component would add — our grid image replaces
 * them, so this keeps the tooltip from showing both.
 */
@Mixin(ItemContainerContents.class)
public class ItemContainerContentsMixin {
	@Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
	private void unlucky$suppressText(CallbackInfo ci) {
		if (InventoryInfo.showContainerGrid()) {
			ci.cancel();
		}
	}
}
