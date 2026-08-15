package unlucky.utility.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.player.QuickStash;
import unlucky.utility.client.module.modules.render.ItemHighlight;

/** Adds QuickStash's take-all/store-all buttons beside supported storage screens. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends Screen {
	private static final int UNLUCKY$BUTTON_SIZE = 20;

	@Shadow
	protected AbstractContainerMenu menu;
	@Shadow
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	protected int imageWidth;

	protected AbstractContainerScreenMixin(Component title) {
		super(title);
	}

	/**
	 * ItemHighlight. Every slot of every menu is drawn through here, so one hook
	 * covers containers, the player inventory and the creative tabs alike. HEAD,
	 * so the fill lands under the item rather than over it.
	 */
	@Inject(method = "extractSlot", at = @At("HEAD"))
	private void unlucky$itemHighlight(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
			CallbackInfo ci) {
		int color = UnluckyClient.INSTANCE.modules.get(ItemHighlight.class).highlight(slot.getItem());
		if (color != 0) {
			graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void unlucky$addQuickStashButtons(CallbackInfo ci) {
		QuickStash quickStash = UnluckyClient.INSTANCE.modules.get(QuickStash.class);
		if (!quickStash.isEnabled() || !QuickStash.supported(this.menu)) {
			return;
		}

		int x = this.leftPos + this.imageWidth + 4;
		this.addRenderableWidget(Button.builder(Component.literal("↑"), b -> quickStash.fillAll(this.menu))
				.bounds(x, this.topPos, UNLUCKY$BUTTON_SIZE, UNLUCKY$BUTTON_SIZE).build());
		this.addRenderableWidget(Button.builder(Component.literal("↓"), b -> quickStash.stealAll(this.menu))
				.bounds(x, this.topPos + UNLUCKY$BUTTON_SIZE + 4, UNLUCKY$BUTTON_SIZE, UNLUCKY$BUTTON_SIZE).build());
	}
}
