package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/** The classic enabled-modules list with slide animations and gradient colors. */
public class ArrayListWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("ArrayList", "Enabled modules list", false));
	public final BooleanSetting bg = add(new BooleanSetting("ArrayList bg", "Backing behind the module list", true));
	public final ModeSetting sort = add(new ModeSetting("Array sort", "Order enabled modules by width, name or category", "Width", "Width", "Name", "Category"));
	public final ModeSetting listOrder = add(new ModeSetting("Array order", "Use the normal sort order or reverse it", "Normal", "Normal", "Reverse"));
	public final ModeSetting background = add(new ModeSetting("Array background", "Individual rows, one grouped backdrop, or no backdrop", "Rows", "Rows", "Grouped", "None"));
	public final NumberSetting spacing = add(new NumberSetting("Array spacing", "Gap between module rows", 0, 0, 4, 1));
	public final ModeSetting suffix = add(new ModeSetting("Array suffix", "Optional information shown after each module", "None", "None", "Category", "Bind"));
	public final BooleanSetting categorySeparators = add(new BooleanSetting("Category separators", "Label category groups while sorting by category", false));
	public final ModeSetting colors = add(new ModeSetting("Array colors",
			"Gradient uses the HUD accent colors. Random assigns a stable hue to every module; "
					+ "RGB cycles a hue wheel; Category uses Future's per-category palette.",
			"Gradient", "Gradient", "Random", "RGB", "Category"));
	public final NumberSetting saturation = add(new NumberSetting("Array saturation",
			"Color intensity for Random and RGB. 0 is grayscale; 1 is fully vivid.",
			0.8, 0.0, 1.0, 0.05));
	public final NumberSetting rgbSpeed = add(new NumberSetting("RGB speed",
			"How quickly RGB colors cycle.", 1.0, 0.1, 5.0, 0.1));
	public final BooleanSetting animate = add(new BooleanSetting("Array animation", "Flow the ArrayList gradient over time", true));
	public final NumberSetting speed = add(new NumberSetting("Array speed", "Gradient flow speed", 1.0, 0.1, 5.0, 0.1));
	public final ModeSetting direction = add(new ModeSetting("Array direction", "Gradient flow direction", "Down", "Down", "Up"));
	public final ModeSetting toggleAnimation = add(new ModeSetting("Array toggle animation", "How rows enter and leave", "Slide", "Slide", "Fade", "None"));
	public final NumberSetting toggleSpeed = add(new NumberSetting("Array toggle speed", "Enter and leave animation duration in milliseconds", 220, 60, 600, 20));


	private final Map<Module, Animation> animations = new HashMap<>();
	// module names never change, so measure each exactly once — the width walk was
	// ~140 glyph measurements per frame across the 70-module loop (Phase 10 Tier 2)
	private int animationDuration = 220;

	public ArrayListWidget() {
		super("ArrayList");
		// Keep the existing gradient controls intact, but only surface settings that
		// affect the currently selected color source in the HUD editor.
		saturation.showWhen(() -> colors.is("Random") || colors.is("RGB"));
		rgbSpeed.showWhen(() -> colors.is("RGB"));
		animate.showWhen(() -> colors.is("Gradient"));
		speed.showWhen(() -> colors.is("Gradient"));
		direction.showWhen(() -> colors.is("Gradient"));
		categorySeparators.showWhen(() -> sort.is("Category"));
		toggleSpeed.showWhen(() -> !toggleAnimation.is("None"));
	}

	private int nameWidth(Module module) {
		// Universal font/text-scale changes affect width, so this must stay live.
		int base = Render2D.width(module.getName());
		String extra = suffixText(module);
		return extra.isEmpty() ? base : base + Render2D.width(" " + extra);
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.0);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		int lineHeight = Math.max(Render2D.FONT_HEIGHT + 2,
				(int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 2);
		int categoryHeight = lineHeight;
		int wantedDuration = toggleSpeed.getInt();
		if (animationDuration != wantedDuration) {
			animationDuration = wantedDuration;
			animations.clear();
		}
		List<Module> visible = new ArrayList<>();
		for (Module module : UnluckyClient.INSTANCE.modules.all()) {
			// Hidden reads as "off" to the animation, so ticking it slides the module out
			// the same way disabling would, instead of popping the line out of the list.
			boolean show = module.isEnabled() && !module.isHidden();
			Animation animation = animations.computeIfAbsent(module, m -> new Animation(animationDuration, show, Easing.CUBIC_OUT));
			animation.setDirection(show);
			if (show || (!toggleAnimation.is("None") && !animation.isCollapsed())) {
				visible.add(module);
			}
		}
		// widest line hugs the docked vertical edge — narrowest on top when docked
		// low, widest on top when docked high, matching every other widget
		switch (sort.get()) {
			case "Name" -> visible.sort(java.util.Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
			case "Category" -> visible.sort(java.util.Comparator
					.comparing((Module m) -> m.getCategory().ordinal()).thenComparing(Module::getName));
			default -> sortBySize(visible, this::nameWidth);
		}
		if (listOrder.is("Reverse")) {
			java.util.Collections.reverse(visible);
		}

		int maxWidth = 0;
		for (Module module : visible) {
			maxWidth = Math.max(maxWidth, nameWidth(module) + 6);
		}
		if (editing && visible.isEmpty()) {
			maxWidth = Render2D.width("ArrayList") + 6;
			Render2D.text(g, "ArrayList", getX() + 3, getY() + 2, Theme.textDim);
		}
		int categoryCount = 0;
		if (sort.is("Category") && categorySeparators.get()) {
			Category last = null;
			for (Module module : visible) {
				if (module.getCategory() != last) {
					last = module.getCategory();
					categoryCount++;
					maxWidth = Math.max(maxWidth, Render2D.width(last.displayName().toUpperCase(java.util.Locale.ROOT)) + 6);
				}
			}
		}
		int rowGap = spacing.getInt();
		int listHeight = Math.max(visible.size() * lineHeight + categoryCount * categoryHeight
				+ Math.max(visible.size() - 1, 0) * rowGap, lineHeight);
		int contentWidth = Math.max(maxWidth, 10);
		setSize(contentWidth, listHeight);
		if (!hasExplicitBackgroundOverride() && bg.get() && background.is("Grouped")) {
			Render2D.hudPanel(g, getX(), getY(), contentWidth, listHeight, true);
		}

		boolean right = alignsRight(g.guiWidth());
		int y = getY();
		int index = 0;
		Category drawnCategory = null;
		for (Module module : visible) {
			if (sort.is("Category") && categorySeparators.get() && module.getCategory() != drawnCategory) {
				drawnCategory = module.getCategory();
				String title = drawnCategory.displayName().toUpperCase(java.util.Locale.ROOT);
				int titleX = right ? getX() + contentWidth - Render2D.width(title) - 3 : getX() + 3;
				Render2D.text(g, title, titleX, y + 1, ColorUtil.withAlpha(futureCategoryColor(drawnCategory), 190));
				y += categoryHeight;
			}
			float slide = toggleAnimation.is("None") ? 1.0f : animations.get(module).value();
			String name = moduleText(module);
			int lineWidth = nameWidth(module) + 6;
			int color = moduleColor(module, index, Math.max(visible.size(), 1));
			int alpha = (int) (255 * slide);
			if (alpha <= 4) {
				index++;
				continue;
			}

			int slideOffset = toggleAnimation.is("Slide") ? (int) ((1.0f - slide) * (lineWidth + 4)) : 0;
			int lineX = right
					? getX() + contentWidth - lineWidth + slideOffset
					: getX() - slideOffset;

			if (!hasExplicitBackgroundOverride() && bg.get() && background.is("Rows")) {
				Render2D.rect(g, lineX, y, lineWidth, lineHeight, ColorUtil.multiplyAlpha(
						Theme.hudBg(bg.get()), slide));
			}
			// accent bar hugs the outer edge
			if (right) {
				Render2D.rect(g, lineX + lineWidth - 1, y, 1, lineHeight, ColorUtil.withAlpha(color, alpha));
				Render2D.text(g, name, lineX + 2, y + 2, ColorUtil.withAlpha(color, alpha));
			} else {
				Render2D.rect(g, lineX, y, 1, lineHeight, ColorUtil.withAlpha(color, alpha));
				Render2D.text(g, name, lineX + 4, y + 2, ColorUtil.withAlpha(color, alpha));
			}

			y += (int) (lineHeight * slide) + rowGap;
			index++;
		}
	}

	private String moduleText(Module module) {
		String extra = suffixText(module);
		return extra.isEmpty() ? module.getName() : module.getName() + " " + extra;
	}

	private String suffixText(Module module) {
		return switch (suffix.get()) {
			case "Category" -> "[" + module.getCategory().displayName() + "]";
			case "Bind" -> module.getKeyBind() < 0 ? "" : "[" + InputConstants.Type.KEYSYM.getOrCreate(module.getKeyBind()).getDisplayName().getString() + "]";
			default -> "";
		};
	}

	/** Resolves this line's color without coupling the ArrayList to either ClickGUI. */
	private int moduleColor(Module module, int index, int total) {
		return switch (colors.get()) {
			case "Random" -> ColorUtil.hsb(stableHue(module), saturation.getFloat(), 1.0f, 255);
			case "RGB" -> ColorUtil.hsb(rgbHue(index, total), saturation.getFloat(), 1.0f, 255);
			case "Category" -> futureCategoryColor(module.getCategory());
			default -> Theme.hudScrollingAccent(index, total); // legacy Gradient mode
		};
	}

	/**
	 * One full hue wheel every four seconds at speed 1.0. The offset spreads visible
	 * lines over that wheel, as a normal animated RGB ArrayList does.
	 */
	private float rgbHue(int index, int total) {
		float base = total <= 1 ? 0.0f : (float) index / total;
		float period = Math.max(200.0f, 4000.0f / rgbSpeed.getFloat());
		float phase = (System.currentTimeMillis() % (long) period) / period;
		return wrapHue(base + phase);
	}

	/**
	 * A module's name is immutable in normal use, so its hash gives Random a stable
	 * color across frames, list reorders and client restarts instead of flickering.
	 */
	private static float stableHue(Module module) {
		long hash = Integer.toUnsignedLong(module.getName().hashCode());
		// Mix Java's relatively similar string hashes into a uniform-looking hue.
		hash ^= hash >>> 16;
		hash *= 0x7FEB352DL;
		hash ^= hash >>> 15;
		hash *= 0x846CA68BL;
		hash ^= hash >>> 16;
		return (hash & 0xFFFFFFFFL) / 4294967296.0f;
	}

	private static float wrapHue(float hue) {
		hue %= 1.0f;
		return hue < 0.0f ? hue + 1.0f : hue;
	}

	/** Classic Future-style category palette: warm combat, blue movement, green world. */
	private static int futureCategoryColor(Category category) {
		return switch (category) {
			case COMBAT -> 0xFFE34343;
			case PLAYER -> 0xFFF0A13A;
			case MOVEMENT -> 0xFF4F9FEA;
			case RENDER -> 0xFFB06CE8;
			case WORLD -> 0xFF58BA6A;
			case MISC -> 0xFFE0C343;
			case CLIENT -> 0xFF3ACFC0;
		};
	}
}
