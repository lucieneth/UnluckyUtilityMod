package unlucky.utility.client.gui.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.world.effect.MobEffectInstance;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.config.ConfigManager;
import unlucky.utility.client.gui.FrameBlur;
import unlucky.utility.client.gui.hud.widgets.ArrayListWidget;
import unlucky.utility.client.gui.hud.widgets.InfoWidget;
import unlucky.utility.client.gui.hud.widgets.WatermarkWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.util.PerfDebug;

public final class HudManager {
	private final List<HudWidget> widgets = new ArrayList<>();
	private static boolean previewData;
	private final unlucky.utility.client.gui.hud.widgets.ItemPickupWidget itemPickups =
			new unlucky.utility.client.gui.hud.widgets.ItemPickupWidget();

	public void init() {
		addPrimary(new WatermarkWidget());
		addPrimary(new ArrayListWidget());
		addPrimary(new InfoWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.TargetHudWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.PlayerModelWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.KeystrokesWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.ArmorHudWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.PotionHudWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.BrewingWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.PrinterWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.MaterialsWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.LayerWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.CoordsWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.SpeedometerWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.InventoryViewerWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.PopCounterWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.SessionInfoWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.ItemCounterWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.RadarWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.CompassBarWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.CustomTextWidget());
		addPrimary(new unlucky.utility.client.gui.hud.widgets.GreeterWidget());
		addPrimary(itemPickups);
	}

	private void addPrimary(HudWidget widget) {
		widget.markPrimaryInstance();
		widgets.add(widget);
	}

	public List<HudWidget> widgets() {
		return widgets;
	}

	/** True when the selected type is one of the registered built-ins with a public no-arg constructor. */
	public boolean canDuplicate(HudWidget source) {
		if (source == null || !duplicateTypeSupported(source.getClass())
				|| primaryOfExactType(source.getClass()) == null) return false;
		try {
			source.getClass().getConstructor();
			return true;
		} catch (NoSuchMethodException ignored) {
			return false;
		}
	}

	/** Creates an independent, persistable view of a built-in HUD widget. */
	public HudWidget duplicate(HudWidget source, int screenWidth, int screenHeight) {
		if (!canDuplicate(source)) return null;
		HudWidget copy = instantiateWhitelisted(source.getClass());
		if (copy == null) return null;
		ConfigManager.copyCompatibleWidgetSettings(source, copy);
		String id = "duplicate:" + UUID.randomUUID();
		copy.markDuplicateInstance(id, nextCopyLabel(source.getClass()));
		copy.placeDuplicateNear(source, screenWidth, screenHeight);
		widgets.add(copy);
		return copy;
	}

	/** Removes live copies before applying a config/profile, leaving built-ins and service references intact. */
	public void clearDuplicates() {
		widgets.removeIf(widget -> !widget.isPrimaryInstance());
	}

	/**
	 * Reconstructs a persisted copy before ConfigManager applies its position and
	 * settings. The type must match an already registered primary, so config data
	 * can never be used to instantiate an arbitrary class.
	 */
	public HudWidget restoreDuplicate(String typeId, String instanceId, String displayName) {
		if (typeId == null || instanceId == null || instanceId.isBlank() || instanceId.length() > 160) return null;
		for (HudWidget widget : widgets) {
			if (instanceId.equals(widget.getInstanceId())) return null;
		}
		HudWidget primary = widgets.stream()
				.filter(HudWidget::isPrimaryInstance)
				.filter(widget -> widget.getWidgetTypeId().equals(typeId))
				.findFirst().orElse(null);
		if (primary == null) return null;
		HudWidget copy = instantiateWhitelisted(primary.getClass());
		if (copy == null) return null;
		copy.markDuplicateInstance(instanceId,
				displayName == null || displayName.isBlank() ? nextCopyLabel(primary.getClass()) : displayName);
		widgets.add(copy);
		return copy;
	}

	private HudWidget primaryOfExactType(Class<?> type) {
		for (HudWidget widget : widgets) {
			if (widget.isPrimaryInstance() && widget.getClass() == type) return widget;
		}
		return null;
	}

	private HudWidget instantiateWhitelisted(Class<?> type) {
		if (!duplicateTypeSupported(type) || primaryOfExactType(type) == null) return null;
		try {
			return (HudWidget) type.getConstructor().newInstance();
		} catch (ReflectiveOperationException | SecurityException e) {
			UnluckyClientMod.LOGGER.error("Failed to create duplicate HUD widget {}", type.getName(), e);
			return null;
		}
	}

	private boolean duplicateTypeSupported(Class<?> type) {
		// Pickup packets intentionally feed the primary service instance returned by
		// itemPickups(). A second ItemPickupWidget would therefore be an empty shell.
		return type != unlucky.utility.client.gui.hud.widgets.ItemPickupWidget.class;
	}

	private String nextCopyLabel(Class<?> type) {
		HudWidget primary = primaryOfExactType(type);
		long instances = widgets.stream().filter(widget -> widget.getClass() == type).count();
		return (primary == null ? type.getSimpleName() : primary.getName()) + " Copy " + (instances + 1);
	}

	/** Editor-wide fake-data flag consumed by widgets that can provide richer previews. */
	public static boolean isPreviewData() {
		return previewData;
	}

	public static void setPreviewData(boolean preview) {
		previewData = preview;
	}

	/**
	 * The one widget of the given type — how anything outside the HUD reads a widget's
	 * settings now that each widget owns them. Null before {@link #init()}, so callers
	 * that run at mod-init time (config load) must tolerate it.
	 */
	@SuppressWarnings("unchecked")
	public <T extends HudWidget> T get(Class<T> type) {
		for (HudWidget widget : widgets) {
			if (widget.isPrimaryInstance() && type.isInstance(widget)) {
				return (T) widget;
			}
		}
		return null;
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
		for (HudWidget widget : widgets) {
			widget.prepareFrame(g.guiWidth(), g.guiHeight(), editing);
		}
		if (PerfDebug.ENABLED) {
			PerfDebug.end("hud.avoidance", start);
		}
		List<HudWidget> blurred = widgets.stream()
				.filter(w -> w.preparedVisible(editing) && w.usesBlurBackground())
				.toList();
		// A frame has exactly one blur in it. We extract before the screen does, so taking
		// it here would leave a menu on top of us with a sharp backdrop and, until this was
		// arbitrated, crash the game outright on the screen's second request. Stand down:
		// under a full-frame menu blur our own blurred rectangles would not be visible.
		if (!blurred.isEmpty() && !FrameBlur.screenWillClaim() && FrameBlur.claim(g)) {
			unlucky.utility.client.gui.clickgui.FuturePanelBlur.beginFrame();
			// Register after claiming but before the frame is drawn — the blur itself is
			// deferred to GuiRenderer, so only that deadline matters. This also covers
			// widgets that render no conventional hudPanel (text-only styles, item grids).
			for (HudWidget w : blurred) {
				unlucky.utility.client.gui.clickgui.FuturePanelBlur.registerPanel(
						w.visualLeft(), w.visualTop(), w.visualWidth(), w.visualHeight());
			}
		}
		for (HudWidget widget : widgets) {
			// Invisible widgets still receive a cheap render call so fade/slide/scale
			// animations can complete; HudWidget returns before drawing at zero progress.
			if (PerfDebug.ENABLED) {
				start = PerfDebug.begin();
			}
			widget.render(g, editing);
			if (PerfDebug.ENABLED) {
				PerfDebug.end("hud." + widget.getName(), start);
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
	 * with none showing. Vanilla stacks toasts top-right in five slots.
	 *
	 * <p>The bottom is the <em>last occupied slot</em>, not the number of toasts:
	 * vanilla never repacks the stack, so an expired toast at the top leaves a hole
	 * and everything below it stays put. Counting toasts would let widgets ride up
	 * into a stack that had not moved, which is what {@code occupiedSlots.length()} —
	 * the highest set bit, plus one — answers directly.
	 */
	private int[] toastBand(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		int lastSlot = ((unlucky.utility.client.mixin.ToastManagerAccessor) mc.gui.toastManager())
				.unlucky$occupiedSlots().length();
		if (lastSlot <= 0) {
			return null;
		}
		return new int[]{g.guiWidth() - net.minecraft.client.gui.components.toasts.Toast.DEFAULT_WIDTH,
				g.guiWidth(), lastSlot * net.minecraft.client.gui.components.toasts.Toast.SLOT_HEIGHT};
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
