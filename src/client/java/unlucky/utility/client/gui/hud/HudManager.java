package unlucky.utility.client.gui.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.world.effect.MobEffectInstance;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.widgets.ArrayListWidget;
import unlucky.utility.client.gui.hud.widgets.InfoWidget;
import unlucky.utility.client.gui.hud.widgets.WatermarkWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.util.PerfDebug;

public final class HudManager {
	private final List<HudWidget> widgets = new ArrayList<>();
	private final unlucky.utility.client.gui.hud.widgets.ItemPickupWidget itemPickups =
			new unlucky.utility.client.gui.hud.widgets.ItemPickupWidget();

	public void init() {
		widgets.add(new WatermarkWidget());
		widgets.add(new ArrayListWidget());
		widgets.add(new InfoWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.TargetHudWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.PlayerModelWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.KeystrokesWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.ArmorHudWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.PotionHudWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.CoordsWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.SpeedometerWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.InventoryViewerWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.PopCounterWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.SessionInfoWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.ItemCounterWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.RadarWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.CompassBarWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.CustomTextWidget());
		widgets.add(new unlucky.utility.client.gui.hud.widgets.GreeterWidget());
		widgets.add(itemPickups);
	}

	public List<HudWidget> widgets() {
		return widgets;
	}

	public unlucky.utility.client.gui.hud.widgets.ItemPickupWidget itemPickups() {
		return itemPickups;
	}

	public void render(GuiGraphicsExtractor g, boolean editing) {
		if (!editing && !UnluckyClient.INSTANCE.modules.get(HudModule.class).isEnabled()) {
			return;
		}
		long start = PerfDebug.ENABLED ? PerfDebug.begin() : 0L;
		applyAvoidance(g, editing);
		if (PerfDebug.ENABLED) {
			PerfDebug.end("hud.avoidance", start);
		}
		for (HudWidget widget : widgets) {
			if (editing || widget.isVisible()) {
				if (PerfDebug.ENABLED) {
					start = PerfDebug.begin();
				}
				widget.render(g, editing);
				if (PerfDebug.ENABLED) {
					PerfDebug.end("hud." + widget.getName(), start);
				}
			}
		}
	}

	/**
	 * Slides HUD widgets out of the way of things vanilla draws over them: down past
	 * the potion icons (top-right), up past the open chat (bottom-left). Both passes
	 * accumulate onto each widget's eased offset, so a widget affected by both nets
	 * out sensibly; widgets stacked together move as a group.
	 */
	private void applyAvoidance(GuiGraphicsExtractor g, boolean editing) {
		for (HudWidget w : widgets) {
			w.setTargetPush(0f);
		}
		if (editing) {
			return;
		}
		avoidTopRight(g);
		avoidChat(g);
	}

	/**
	 * Downward slide for widgets under the vanilla top-right furniture: the
	 * status-effect icons and any visible toasts (module toggles, advancements,
	 * the music "now playing" card). The two rectangles are merged into one band
	 * so a widget under both is pushed once, past the lower of the two — not the
	 * sum of both pushes.
	 */
	private void avoidTopRight(GuiGraphicsExtractor g) {
		int[] band = potionBand(g);
		int[] toasts = toastBand(g);
		if (toasts != null) {
			band = band == null ? toasts : new int[]{
					Math.min(band[0], toasts[0]), Math.max(band[1], toasts[1]), Math.max(band[2], toasts[2])};
		}
		if (band == null) {
			return;
		}
		int bandLeft = band[0], bandRight = band[1], bandBottom = band[2];
		int gw = g.guiWidth();
		int gh = g.guiHeight();

		// widgets whose column overlaps the icons, top-down
		List<HudWidget> column = new ArrayList<>();
		for (HudWidget w : widgets) {
			if (!w.isVisible() || w.getWidth() <= 0) {
				continue;
			}
			int left = w.naturalLeft(gw);
			int right = left + w.getWidth();
			if (right > bandLeft && left < bandRight) {
				column.add(w);
			}
		}
		column.sort(Comparator.comparingInt(w -> w.naturalTop(gh)));

		final int clear = 2; // gap kept below the icons and between stacked widgets
		final int chain = 8; // widgets closer than this are treated as one stack
		int prevOrigBottom = Integer.MIN_VALUE;
		int prevNewBottom = Integer.MIN_VALUE;
		for (HudWidget w : column) {
			int top = w.naturalTop(gh);
			int floor = Integer.MIN_VALUE;
			if (top < bandBottom + clear) { // would overlap the icons
				floor = bandBottom + clear;
			}
			// chain only to a widget genuinely stacked just above (a real gap, same column);
			// a negative gap means they overlap (side-by-side), which must not drag them together
			int gapBelow = top - prevOrigBottom;
			if (prevOrigBottom != Integer.MIN_VALUE && gapBelow >= 0 && gapBelow <= chain) {
				floor = Math.max(floor, prevNewBottom + gapBelow);
			}
			int newTop = Math.max(top, floor);
			w.addTargetPush(newTop - top);
			prevOrigBottom = top + w.getHeight();
			prevNewBottom = newTop + w.getHeight();
		}
	}

	/**
	 * Upward slide for widgets sitting under the bottom input bar while chat is open.
	 * Only the input bar pushes the HUD — the message log ("green") slides in from the
	 * left and leaves widgets alone. The bar is the full-width strip vanilla fills at
	 * {@code [2, width-2] x [height-14, height-2]} in {@code ChatScreen}. Mirrors the
	 * potion cascade, flipped: bottom-most widget lifts first and raises the ceiling
	 * for the stacked widget above it.
	 */
	private void avoidChat(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		ChatComponent chat = mc.gui.hud.getChat();
		if (chat == null || !chat.isChatFocused()) { // the input bar is only up while chat is open
			return;
		}
		int gw = g.guiWidth();
		int gh = g.guiHeight();
		int left = 2;
		int right = gw - 2;
		int bottom = gh - 2;  // input bar bottom
		int top = gh - 14;    // input bar top

		// widgets whose column overlaps the chat and that dip into the occupied area
		List<HudWidget> column = new ArrayList<>();
		for (HudWidget w : widgets) {
			if (!w.isVisible() || w.getWidth() <= 0) {
				continue;
			}
			int wl = w.naturalLeft(gw);
			int wr = wl + w.getWidth();
			if (wr <= left || wl >= right) {
				continue;
			}
			int wTop = w.naturalTop(gh);
			int wBottom = wTop + w.getHeight();
			if (wBottom <= top || wTop >= bottom) { // fully clear above / below the chat
				continue;
			}
			column.add(w);
		}
		// bottom-most first, so a lifted widget raises the ceiling for the one above
		column.sort(Comparator.comparingInt((HudWidget w) -> w.naturalTop(gh) + w.getHeight()).reversed());

		final int clear = 2;
		final int chain = 8;
		int prevOrigTop = Integer.MAX_VALUE;
		int prevNewTop = Integer.MAX_VALUE;
		for (HudWidget w : column) {
			int wTop = w.naturalTop(gh);
			int wBottom = wTop + w.getHeight();
			int ceiling = Integer.MAX_VALUE;
			if (wBottom > top - clear) { // dips into the chat
				ceiling = top - clear;
			}
			// chain only to a widget genuinely stacked just below (a real gap, same column);
			// a negative gap means they overlap (side-by-side), which must not drag them together
			int gapAbove = prevOrigTop - wBottom;
			if (prevOrigTop != Integer.MAX_VALUE && gapAbove >= 0 && gapAbove <= chain) {
				ceiling = Math.min(ceiling, prevNewTop - gapAbove);
			}
			int newBottom = Math.min(wBottom, ceiling);
			int pushUp = wBottom - newBottom; // >= 0
			w.addTargetPush(-pushUp);
			prevOrigTop = wTop;
			prevNewTop = wTop - pushUp;
		}
	}

	/**
	 * The screen rectangle visible toasts occupy: {left, right, bottom}, or null
	 * with none showing. Vanilla stacks toasts top-right in five 32px slots,
	 * 160px wide; occupancy comes from {@code ToastManager.freeSlotCount} via
	 * the accessor mixin (multi-slot toasts like the music card count correctly).
	 */
	private int[] toastBand(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		int free = ((unlucky.utility.client.mixin.ToastManagerAccessor) mc.gui.toastManager())
				.unlucky$freeSlotCount();
		int occupied = 5 - free; // ToastManager.SLOT_COUNT is private
		if (occupied <= 0) {
			return null;
		}
		return new int[]{g.guiWidth() - 160, g.guiWidth(), occupied * 32};
	}

	/**
	 * The screen rectangle the vanilla status-effect icons occupy: {left, right,
	 * bottom}, or null when nothing is shown. Mirrors {@code Hud.extractEffects} —
	 * beneficial effects on the top row, harmful on a second row 26px lower, icons
	 * 25px apart from the right edge.
	 */
	private int[] potionBand(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return null;
		}
		// vanilla moves the icons into the inventory-style screen instead of the corner
		if (mc.gui.screen() != null && mc.gui.screen().showsActiveEffects()) {
			return null;
		}
		int beneficial = 0;
		int harmful = 0;
		for (MobEffectInstance e : mc.player.getActiveEffects()) {
			if (!e.showIcon()) {
				continue;
			}
			if (e.getEffect().value().isBeneficial()) {
				beneficial++;
			} else {
				harmful++;
			}
		}
		if (beneficial == 0 && harmful == 0) {
			return null;
		}
		int topOffset = mc.isDemo() ? 16 : 1;
		int maxCount = Math.max(beneficial, harmful);
		int left = g.guiWidth() - 25 * maxCount;
		int right = g.guiWidth();
		int bottom = (harmful > 0 ? topOffset + 26 : topOffset) + 24;
		return new int[]{left, right, bottom};
	}
}
