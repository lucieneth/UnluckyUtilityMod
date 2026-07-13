package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.gui.skins.SkinPreviewWidget;
import unlucky.utility.client.gui.skins.SkinRender;
import unlucky.utility.client.gui.skins.SkinsScreen;

/**
 * The main-menu skin panel (Lucien's mockup): a live mouse-following player
 * preview on the left, with <b>Edit</b> (opens {@link SkinsScreen}) and
 * <b>NameMC</b> (profile in the browser) underneath. Re-added on every
 * {@code init}, so it survives resizes like any vanilla widget.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	private static final int PREVIEW_W = 100;
	private static final int PREVIEW_H = 110;

	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void unlucky$skinPanel(CallbackInfo ci) {
		// centered in the empty strip left of the menu button column
		int px = Math.max(8, (width / 2 - 104 - PREVIEW_W) / 2 + 8);
		int py = height / 2 - (PREVIEW_H + 24) / 2;
		addRenderableWidget(new SkinPreviewWidget(px, py, PREVIEW_W, PREVIEW_H, SkinRender::ownSkin));
		int half = (PREVIEW_W - 4) / 2;
		addRenderableWidget(Button.builder(Component.literal("Edit"),
						button -> minecraft.gui.setScreen(new SkinsScreen(this)))
				.bounds(px, py + PREVIEW_H + 4, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("NameMC"),
						button -> Util.getPlatform().openUri(
								"https://namemc.com/profile/" + minecraft.getUser().getProfileId()))
				.bounds(px + half + 4, py + PREVIEW_H + 4, PREVIEW_W - half - 4, 20).build());
	}
}
