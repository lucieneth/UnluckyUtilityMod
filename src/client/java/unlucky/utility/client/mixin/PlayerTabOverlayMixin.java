package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.util.HeadRenderer;

/**
 * Friend and Unlucky marks in the tablist.
 *
 * <p>The friend mark rides on the player's face as a corner badge
 * ({@link #unlucky$faceBadge}) rather than as text before their name: the head
 * is already the row's identity, so the mark costs no width and the name sits
 * flush against the face like vanilla. Only when there is no face to badge —
 * vanilla skips them on cracked servers — does {@link Friends#tablistNameColor}
 * hand the mark back to the name, which is why both paths exist.
 *
 * <p>The Unlucky star still trails the name: it marks the client someone runs,
 * not who they are to you, and there is only one face to hang a badge on.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	private static final String EXTRACT = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
			+ "ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V";
	/** Pixels added between the face and the name — vanilla leaves 1, half a space is 2. */
	private static final int NAME_GAP = 2;

	/**
	 * {@code getNameForDisplay} is the single source for the shown name — the
	 * overlay calls it both when measuring column widths and when drawing, so
	 * wrapping the return value here keeps layout and render consistent.
	 */
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void unlucky$friendDot(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		java.util.UUID uuid = info.getProfile().id();
		Component name = cir.getReturnValue();

		var unluckyUsers = UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.misc.UnluckyUsers.class);
		int marker = unluckyUsers.markerFor(uuid);
		int friendColor = UnluckyClient.INSTANCE.modules.get(Friends.class).tablistNameColor(uuid);
		if (marker == 0 && friendColor == 0) {
			return;
		}

		// mark name ✦ — the friend mark leads, the Unlucky star trails. The mark
		// gets a LEADING space too: the vanilla skin face sits immediately left of
		// this string, and a mark flush against it read as part of the face
		// (Lucien: "really close ... uneven"). One space is the face's padding.
		Component decorated = name;
		if (friendColor != 0) {
			String mark = UnluckyClient.INSTANCE.modules.get(Friends.class).markerText();
			decorated = Component.empty()
					.append(Component.literal(" " + mark + " ").withColor(friendColor & 0xFFFFFF))
					.append(decorated);
		}
		if (marker != 0) {
			decorated = Component.empty()
					.append(decorated)
					.append(Component.literal(" " + unluckyUsers.markerText()).withColor(marker & 0xFFFFFF));
		}
		cir.setReturnValue(decorated);
	}

	/**
	 * The one face draw in the row loop, wrapped so the badge lands in the same
	 * pass at exactly the coordinates vanilla used — no second guess at where
	 * the 8px head ended up. The row's {@code PlayerInfo} is the only one live at
	 * this point in the method, so {@code @Local} resolves it unambiguously.
	 */
	@WrapOperation(method = EXTRACT, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/PlayerFaceExtractor;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;IIIZZI)V"))
	private void unlucky$faceBadge(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int size,
			boolean hat, boolean upsideDown, int color, Operation<Void> original,
			@Local PlayerInfo info) {
		original.call(graphics, texture, x, y, size, hat, upsideDown, color);
		int dot = UnluckyClient.INSTANCE.modules.get(Friends.class).tablistBadgeColor(info.getProfile().id());
		HeadRenderer.badge(graphics, x, y, size, dot, 255);
	}

	/**
	 * Nudges the name off the face. Vanilla advances the cursor by 9 for an 8px
	 * head, so the name lands 1px from the chin — fine when a friend mark used to
	 * sit in between, cramped now that the mark moved onto the head itself.
	 *
	 * <p>Only the name string moves. The x cursor is shared with the score block
	 * and the ping icon (the ping is drawn at {@code cursor - 9 + columnWidth}),
	 * so shifting the local itself would walk the whole right-hand side of the
	 * row 2px over and push the score into the ping bars. The column keeps 13px
	 * of built-in padding, which absorbs this.
	 *
	 * <p>The {@code Component} overload is the player name and nothing else here —
	 * the header and footer draw through the {@code FormattedCharSequence} one.
	 * No gap without a face: vanilla only draws heads on an online-mode
	 * connection, and there is nothing to move away from on a cracked server.
	 */
	@WrapOperation(method = EXTRACT, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void unlucky$nameGap(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color,
			Operation<Void> original) {
		var connection = Minecraft.getInstance().getConnection();
		boolean faced = connection != null && connection.onlineMode();
		original.call(graphics, font, text, faced ? x + NAME_GAP : x, y, color);
	}
}
