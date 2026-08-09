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

		// The friend mark leads. The Unlucky mark is inserted immediately after the
		// actual profile name instead of after the whole display component: servers
		// commonly append health, ranks, or other live data to that component.
		Component decorated = name;
		if (marker != 0) {
			Component mark = Component.literal(" " + unluckyUsers.markerText()).withColor(marker & 0xFFFFFF);
			decorated = unlucky$insertAfterUsername(decorated, info.getProfile().name(), mark);
		}
		if (friendColor != 0) {
			String mark = UnluckyClient.INSTANCE.modules.get(Friends.class).markerText();
			decorated = Component.empty()
					.append(Component.literal(" " + mark + " ").withColor(friendColor & 0xFFFFFF))
					.append(decorated);
		}
		cir.setReturnValue(decorated);
	}

	/**
	 * Inserts a marker after the username while preserving every styled segment
	 * before and after it. Flattening resolves inherited styles first, so a server
	 * can color the name and its custom HP independently without either leaking
	 * into our marker or being discarded.
	 */
	private static Component unlucky$insertAfterUsername(Component displayName, String username, Component marker) {
		if (username == null || username.isEmpty()) {
			return displayName;
		}
		var parts = displayName.toFlatList();
		int cursor = 0;
		int exactPart = -1;
		for (Component part : parts) {
			if (part.getString().equals(username)) exactPart = cursor;
			cursor += part.getString().length();
		}
		int start = exactPart >= 0 ? exactPart : unlucky$usernameStart(displayName.getString(), username);
		if (start < 0) {
			// Nickname-only display components have no reliable username boundary,
			// so do not guess and accidentally place the mark after a server suffix.
			return displayName;
		}

		int insertion = start + username.length();
		var rebuilt = Component.empty();
		cursor = 0;
		boolean inserted = false;
		for (Component part : parts) {
			String text = part.getString();
			int end = cursor + text.length();
			if (!inserted && insertion >= cursor && insertion <= end) {
				int split = insertion - cursor;
				if (split > 0) rebuilt.append(Component.literal(text.substring(0, split)).setStyle(part.getStyle()));
				rebuilt.append(marker);
				if (split < text.length()) rebuilt.append(Component.literal(text.substring(split)).setStyle(part.getStyle()));
				inserted = true;
			} else {
				rebuilt.append(part.copy());
			}
			cursor = end;
		}
		return inserted ? rebuilt : displayName;
	}

	/** Use the last complete player-name token if the same text occurs in a prefix. */
	private static int unlucky$usernameStart(String rendered, String username) {
		int completeToken = -1;
		for (int from = 0; ; ) {
			int found = rendered.indexOf(username, from);
			if (found < 0) return completeToken;
			int end = found + username.length();
			boolean leftBoundary = found == 0 || !unlucky$isUsernameCharacter(rendered.charAt(found - 1));
			boolean rightBoundary = end == rendered.length() || !unlucky$isUsernameCharacter(rendered.charAt(end));
			if (leftBoundary && rightBoundary) completeToken = found;
			from = found + 1;
		}
	}

	private static boolean unlucky$isUsernameCharacter(char c) {
		return c == '_' || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
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
