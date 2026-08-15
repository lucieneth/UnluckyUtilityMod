package unlucky.utility.client.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.module.modules.render.BetterTab;
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
	 * BetterTab's name pass, after the friend and Unlucky marks rather than instead of them.
	 *
	 * <p>A second injection on the same method rather than folding it into the one above, because
	 * the two answer different questions: that one decides what <em>this client</em> adds, this one
	 * decides what the player wants kept of what the server sent. Ordering between two injections
	 * at the same point is undefined in general — it does not matter here because BetterTab is
	 * given whatever the marks produced and may replace it wholesale.
	 */
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void unlucky$betterTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		BetterTab betterTab = UnluckyClient.INSTANCE.modules.get(BetterTab.class);
		if (betterTab.isEnabled()) {
			cir.setReturnValue(betterTab.decorate(info, cir.getReturnValue()));
		}
	}

	/**
	 * Filter, sort and cap in the one place vanilla decides who is in the list.
	 *
	 * <p>{@code getPlayerInfos} is called once per extract and its result feeds both the column
	 * maths and the row loop, so replacing it here means the layout is computed for exactly the
	 * rows that get drawn. Doing any of the three later would leave the two disagreeing — a cap
	 * applied at draw time still reserves width for the rows it then refuses to draw.
	 */
	@Inject(method = "getPlayerInfos", at = @At("RETURN"), cancellable = true)
	private void unlucky$betterTabRows(CallbackInfoReturnable<List<PlayerInfo>> cir) {
		BetterTab betterTab = UnluckyClient.INSTANCE.modules.get(BetterTab.class);
		if (betterTab.isEnabled()) {
			cir.setReturnValue(betterTab.arrange(cir.getReturnValue()));
		}
	}

	/**
	 * Rows per column.
	 *
	 * <p>{@code MAX_ROWS_PER_COL} is a compile-time constant, so it is inlined into the layout
	 * arithmetic and there is no field or method to intercept — {@code @ModifyConstant} on the
	 * single {@code 20} in this method is the only handle there is. If a future version gains a
	 * second one this fails at load rather than silently changing the wrong number, which is why
	 * it is worth doing here and not with a wider match.
	 */
	@ModifyConstant(method = EXTRACT, constant = @Constant(intValue = 20))
	private int unlucky$columnHeight(int vanilla) {
		return UnluckyClient.INSTANCE.modules.get(BetterTab.class).rowsPerColumn(vanilla);
	}

	/**
	 * Header and footer, hidden by making vanilla believe the server sent none.
	 *
	 * <p>Both are read behind a null check that guards the text <em>and</em> its background band,
	 * so answering null skips the whole block. Suppressing the draw calls instead would leave two
	 * empty dark bands above and below the list.
	 */
	@ModifyExpressionValue(method = EXTRACT, at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;header:Lnet/minecraft/network/chat/Component;",
			opcode = Opcodes.GETFIELD))
	private Component unlucky$hideHeader(Component header) {
		return UnluckyClient.INSTANCE.modules.get(BetterTab.class).showsHeaderFooter() ? header : null;
	}

	@ModifyExpressionValue(method = EXTRACT, at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;footer:Lnet/minecraft/network/chat/Component;",
			opcode = Opcodes.GETFIELD))
	private Component unlucky$hideFooter(Component footer) {
		return UnluckyClient.INSTANCE.modules.get(BetterTab.class).showsHeaderFooter() ? footer : null;
	}

	/**
	 * The ping column.
	 *
	 * <p>Vanilla's whole ping display is this one call, which makes it the only place the four
	 * modes can be expressed without any of them fighting the others. Exact latency is drawn
	 * right-aligned into the same slot the bars would have occupied, so the column width vanilla
	 * already reserved is the width used.
	 */
	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
	private void unlucky$latency(GuiGraphicsExtractor graphics, int width, int x, int y,
			PlayerInfo info, CallbackInfo ci) {
		BetterTab betterTab = UnluckyClient.INSTANCE.modules.get(BetterTab.class);
		if (!betterTab.isEnabled()) {
			return;
		}
		if (betterTab.showsExactLatency()) {
			Font font = Minecraft.getInstance().font;
			String text = betterTab.latencyText(info);
			graphics.text(font, text, x + width - font.width(text), y, 0xFFAAAAAA);
		}
		if (!betterTab.showsPingBars()) {
			ci.cancel();
		}
	}

	/** The server's scoreboard column, which is the server's and not always wanted. */
	@Inject(method = "extractTablistScore", at = @At("HEAD"), cancellable = true)
	private void unlucky$score(CallbackInfo ci) {
		if (!UnluckyClient.INSTANCE.modules.get(BetterTab.class).showsScore()) {
			ci.cancel();
		}
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
		if (!UnluckyClient.INSTANCE.modules.get(BetterTab.class).showsHeads()) {
			// The cursor advance that follows this call is vanilla's, so the name still lands where
			// a head would have been. That is deliberate: the column widths were measured with the
			// head included, and shuffling the name left would push the whole row out of its column.
			return;
		}
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
	 *
	 * <p>It is also the one call per row that has both the row's {@code PlayerInfo} and its
	 * screen position in scope, which is why BetterTab's row tint is drawn from here rather than
	 * by wrapping the background fill itself. That fill is one of four in this method and runs
	 * <em>before</em> the row's {@code PlayerInfo} is read, so selecting it would take an ordinal
	 * and a raw local index; this takes one. The tint lands on top of vanilla's background instead
	 * of replacing it, which is what a tint should do.
	 *
	 * <p><b>{@code columnWidth} is bound by local index.</b> There is no other handle on it — it
	 * is a plain {@code int} among many — and a wrong one is a load-time failure rather than a
	 * silently mis-sized rectangle, which is the trade being made.
	 */
	@WrapOperation(method = EXTRACT, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void unlucky$nameGap(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color,
			Operation<Void> original, @Local PlayerInfo info, @Local(index = 15) int columnWidth) {
		var connection = Minecraft.getInstance().getConnection();
		BetterTab betterTab = UnluckyClient.INSTANCE.modules.get(BetterTab.class);
		boolean faced = connection != null && connection.onlineMode() && betterTab.showsHeads();

		int tint = betterTab.rowTint(info);
		if (tint != 0) {
			// The head, if there was one, sits 9px to the left of where the name starts.
			int left = faced ? x - 9 : x;
			graphics.fill(left, y - 1, left + columnWidth, y + 8, tint);
		}
		int nameColor = betterTab.keepsVanillaGamemodeColor() ? color : -1;
		original.call(graphics, font, text, faced ? x + NAME_GAP : x, y, nameColor);
	}
}
