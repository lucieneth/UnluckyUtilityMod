package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.player.QuickStash;

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
