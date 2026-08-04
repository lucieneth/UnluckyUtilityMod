package unlucky.utility.client.gui.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.Setting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;

/**
 * A draggable HUD element. Position is stored as a fraction of the available
 * screen space, so widgets keep their place when the window is resized.
 *
 * <p><b>A widget owns its settings.</b> They are declared here, not in HudModule, and
 * the editor's right-click popup is generated from {@link #settings()} — so a new
 * widget cannot ship with options that are unreachable, which is exactly what happened
 * while a hand-written switch decided what each popup listed.
 */
public abstract class HudWidget {
	protected static final int MARGIN = 8;
	private static final ThreadLocal<HudWidget> ACTIVE_STYLE = new ThreadLocal<>();

	private final List<Setting<?>> settings = new ArrayList<>();
	private final String name;
	/*
	 * The built-in widget keeps its legacy name as the config key. Copies receive a
	 * generated ID, which lets several instances of the same widget survive a save
	 * without overwriting one another in ConfigManager's JSON object.
	 */
	private String instanceId;
	private String editorDisplayName;
	private boolean primaryInstance = true;
	// Appended lazily after concrete widgets declare their own toggle/settings,
	// preserving the editor's first-setting-is-toggle convention.
	private boolean editorSettingsAdded;
	private final BooleanSetting locked = new BooleanSetting("Layout locked", "Prevent this widget from being moved in the HUD editor", false);
	private final ModeSetting anchor = new ModeSetting("Screen anchor", "Dock this widget to an edge or keep its free position",
			"Free", "Free", "Top left", "Top", "Top right", "Left", "Center", "Right", "Bottom left", "Bottom", "Bottom right");
	private final NumberSetting anchorMargin = new NumberSetting("Anchor margin", "Distance from a docked screen edge", 8, 0, 40, 1);
	private final NumberSetting widgetScale = new NumberSetting("Widget scale", "Scale this complete widget", 1.0, 0.5, 2.0, 0.05);
	private final NumberSetting opacity = new NumberSetting("Widget opacity", "Opacity of this widget's HUD drawing", 100, 10, 100, 1);
	private final NumberSetting padding = new NumberSetting("Extra padding", "Additional space around this widget", 0, 0, 12, 1);
	private final ModeSetting backgroundMode = new ModeSetting("Background mode", "Override this widget's normal panel treatment",
			"Widget", "Widget", "None", "Flat", "Gradient", "Glass", "Blur");
	private final NumberSetting cornerRadius = new NumberSetting("Corner radius", "Round this widget's panel corners", 4, 0, 12, 1);
	private final ModeSetting borderMode = new ModeSetting("Border mode", "Override this widget's normal panel border",
			"Widget", "Widget", "Off", "Static", "Animated");
	private final ModeSetting accentMode = new ModeSetting("Accent mode", "Color source for this widget's accent chrome",
			"Theme", "Theme", "Category", "RGB", "Static", "Gradient");
	private final ColorSetting accentColor = new ColorSetting("Accent color", "Static color or gradient start", 0xFF55DDEE);
	private final ColorSetting accentColor2 = new ColorSetting("Accent color 2", "Gradient end color", 0xFF8B5CFF);
	private final NumberSetting rgbSpeed = new NumberSetting("RGB speed", "Speed of this widget's rainbow accent", 1.0, 0.1, 5.0, 0.1);
	private final NumberSetting textScale = new NumberSetting("Text scale", "Scale text independently inside this widget", 1.0, 0.75, 1.5, 0.05);
	private final ModeSetting fontStyle = new ModeSetting("Text font", "Vanilla-compatible display font style", "Normal",
			"Normal", "Small caps", "Fullwidth", "Bold", "Script", "Fraktur", "Circled");
	private final ModeSetting textStyle = new ModeSetting("Text style", "Text shadow or outline treatment", "Shadow", "Plain", "Shadow", "Outline");
	private final BooleanSetting uppercase = new BooleanSetting("Uppercase text", "Render this widget's HUD text in uppercase", false);
	private final ModeSetting animation = new ModeSetting("Animation", "How this widget appears and disappears", "Off", "Off", "Fade", "Slide", "Scale");
	private final NumberSetting animationSpeed = new NumberSetting("Animation speed", "Speed of widget visibility transitions", 1.0, 0.1, 4.0, 0.1);
	private final ModeSetting animationDirection = new ModeSetting("Animation direction", "Direction used by slide animation", "Right", "Left", "Right", "Up", "Down");
	private final ModeSetting density = new ModeSetting("Layout density", "Compact or normal internal spacing", "Normal", "Compact", "Normal");
	private final ModeSetting orientation = new ModeSetting("Layout orientation", "Override content direction where the widget supports it", "Widget", "Widget", "Horizontal", "Vertical");
	private final ModeSetting contentAlignment = new ModeSetting("Content alignment", "Alignment used by text and list content", "Auto", "Auto", "Left", "Center", "Right");
	private double fracX = Double.NaN;
	private double fracY = Double.NaN;
	// absolute position and size of the last rendered frame, for drawing and hit testing
	private int absX;
	private int absY;
	private int lastWidth;
	private int lastHeight;
	// which screen edge the widget hugs, so content can justify toward it
	private boolean anchorRight;
	private boolean anchorBottom;
	// potion-avoidance: a downward slide the manager asks for, eased toward each frame
	private float pushY;
	private float targetPushY;
	private long lastPushNanos;
	private float visibility = 1.0f;
	private long lastVisibilityNanos;
	private float frameOpacity = 1.0f;
	private float frameTransitionScale = 1.0f;
	private float frameSlideX;
	private float frameSlideY;
	private boolean framePrepared;
	private boolean sharedPanelClaimed;
	private boolean sharedWrapperPass;

	protected HudWidget(String name) {
		this.name = name;
		this.instanceId = "primary:" + getClass().getName();
		this.editorDisplayName = name;
	}

	protected static Minecraft mc() {
		return Minecraft.getInstance();
	}

	protected <T extends Setting<?>> T add(T setting) {
		settings.add(setting);
		return setting;
	}

	/** Everything the editor's right-click popup shows, in declaration order. */
	public List<Setting<?>> settings() {
		ensureEditorSettings();
		return settings;
	}

	private void ensureEditorSettings() {
		if (!editorSettingsAdded) {
			editorSettingsAdded = true;
			settings.add(locked);
			settings.add(anchor);
			settings.add(anchorMargin);
			settings.add(widgetScale);
			settings.add(opacity);
			settings.add(padding);
			settings.add(backgroundMode);
			settings.add(cornerRadius);
			settings.add(borderMode);
			settings.add(accentMode);
			settings.add(accentColor);
			settings.add(accentColor2);
			settings.add(rgbSpeed);
			settings.add(textScale);
			settings.add(fontStyle);
			settings.add(textStyle);
			settings.add(uppercase);
			settings.add(animation);
			settings.add(animationSpeed);
			settings.add(animationDirection);
			settings.add(density);
			settings.add(orientation);
			settings.add(contentAlignment);
			anchorMargin.showWhen(() -> !anchor.is("Free"));
			accentColor.showWhen(() -> accentMode.is("Static") || accentMode.is("Gradient"));
			accentColor2.showWhen(() -> accentMode.is("Gradient"));
			rgbSpeed.showWhen(() -> accentMode.is("RGB"));
			animationSpeed.showWhen(() -> !animation.is("Off"));
			animationDirection.showWhen(() -> animation.is("Slide"));
		}
	}

	public boolean isLayoutLocked() {
		return locked.get();
	}

	public void toggleLayoutLocked() {
		locked.set(!locked.get());
	}

	/** Hides the widget using its own persistent visibility toggle. */
	public void hide() {
		BooleanSetting visibilityToggle = toggle();
		if (visibilityToggle != null) visibilityToggle.set(false);
	}

	/** Restores the subclass's default free placement. */
	public void resetPosition() {
		anchor.set("Free");
		fracX = Double.NaN;
		fracY = Double.NaN;
		applyDefaultPosition();
	}

	/**
	 * The widget's own on/off switch — by convention the first setting it declares,
	 * which is what the editor's widget list toggles on a left click.
	 */
	public BooleanSetting toggle() {
		return !settings.isEmpty() && settings.getFirst() instanceof BooleanSetting b ? b : null;
	}

	public String getName() {
		return name;
	}

	/** Stable identity for this concrete HUD instance (including copies). */
	public String getInstanceId() {
		return instanceId;
	}

	/** Human-readable label used by the editor; copies include a clear suffix. */
	public String getDisplayName() {
		return editorDisplayName;
	}

	/** Built-ins are the authoritative instances returned by HudManager#get. */
	public boolean isPrimaryInstance() {
		return primaryInstance;
	}

	/**
	 * Primary widgets deliberately retain the old name-keyed config layout. Copies
	 * use their stable instance ID, so old configs remain valid and new copies do
	 * not collide with the primary or with one another.
	 */
	public String getConfigKey() {
		return primaryInstance ? name : instanceId;
	}

	/** Type token persisted for reconstructing copies before their settings load. */
	public String getWidgetTypeId() {
		return getClass().getName();
	}

	void markPrimaryInstance() {
		primaryInstance = true;
		instanceId = "primary:" + getClass().getName();
		editorDisplayName = name;
	}

	void markDuplicateInstance(String id, String displayName) {
		primaryInstance = false;
		instanceId = id;
		editorDisplayName = displayName == null || displayName.isBlank()
				? name + " Copy"
				: displayName;
	}

	/**
	 * Starts a copy close to its source while keeping the whole widget inside the
	 * Minecraft GUI. Rendered dimensions are copied first because a new instance
	 * has not had a frame in which to measure itself yet.
	 */
	void placeDuplicateNear(HudWidget source, int screenWidth, int screenHeight) {
		lastWidth = source.lastWidth;
		lastHeight = source.lastHeight;
		int min = MARGIN;
		int maxX = Math.max(min, screenWidth - getWidth() - MARGIN);
		int maxY = Math.max(min, screenHeight - getHeight() - MARGIN);
		int x = source.getX() + 12 <= maxX ? source.getX() + 12 : source.getX() - 12;
		int y = source.getY() + 12 <= maxY ? source.getY() + 12 : source.getY() - 12;
		moveTo(Math.clamp(x, min, maxX), Math.clamp(y, min, maxY), screenWidth, screenHeight);
	}

	public int getX() {
		return absX;
	}

	public int getY() {
		return absY;
	}

	public int getWidth() {
		return Math.max(1, Math.round((lastWidth + padding.getInt() * 2) * effectiveWidgetScale()));
	}

	public int getHeight() {
		return Math.max(1, Math.round((lastHeight + padding.getInt() * 2) * effectiveWidgetScale()));
	}

	private float effectiveWidgetScale() {
		return widgetScale.getFloat() * (density.is("Compact") ? 0.88f : 1.0f);
	}

	/** The content size before per-widget scale and extra padding are applied. */
	public int getContentWidth() { return lastWidth; }
	public int getContentHeight() { return lastHeight; }

	public double getFracX() {
		return Double.isNaN(fracX) ? 0.0 : fracX;
	}

	public double getFracY() {
		return Double.isNaN(fracY) ? 0.0 : fracY;
	}

	public void setFractions(double fx, double fy) {
		this.fracX = Math.clamp(fx, 0.0, 1.0);
		this.fracY = Math.clamp(fy, 0.0, 1.0);
	}

	/** Move so the widget's top-left lands at (x, y) on a screen of the given size. */
	public void moveTo(int x, int y, int screenWidth, int screenHeight) {
		anchor.set("Free");
		int availableX = Math.max(screenWidth - getWidth() - 2 * MARGIN, 1);
		int availableY = Math.max(screenHeight - getHeight() - 2 * MARGIN, 1);
		setFractions((x - MARGIN) / (double) availableX, (y - MARGIN) / (double) availableY);
	}

	/** Exact one-axis alignment action used by the editor's contextual toolbar. */
	public void alignHorizontal(String side, int screenWidth, int screenHeight) {
		int x = switch (side) {
			case "Left" -> MARGIN;
			case "Right" -> screenWidth - getWidth() - MARGIN;
			default -> (screenWidth - getWidth()) / 2;
		};
		moveTo(x, naturalTop(screenHeight), screenWidth, screenHeight);
	}

	/** Exact one-axis alignment action used by the editor's contextual toolbar. */
	public void alignVertical(String side, int screenWidth, int screenHeight) {
		int y = switch (side) {
			case "Top" -> MARGIN;
			case "Bottom" -> screenHeight - getHeight() - MARGIN;
			default -> (screenHeight - getHeight()) / 2;
		};
		moveTo(naturalLeft(screenWidth), y, screenWidth, screenHeight);
	}

	protected void setSize(int width, int height) {
		this.lastWidth = width;
		this.lastHeight = height;
	}

	/** Natural (un-pushed) left edge for a screen of the given width. */
	public int naturalLeft(int screenWidth) {
		if (Double.isNaN(fracX)) {
			applyDefaultPosition();
		}
		return anchoredLeft(screenWidth);
	}

	/** Natural (un-pushed) top edge for a screen of the given height. */
	public int naturalTop(int screenHeight) {
		if (Double.isNaN(fracY)) {
			applyDefaultPosition();
		}
		return anchoredTop(screenHeight);
	}

	/**
	 * Sets the desired vertical slide (avoidance). The manager resets this to 0 each
	 * frame for every widget; {@link #render} eases the actual offset toward it, so
	 * widgets glide when they need to dodge and back when they don't. Positive slides
	 * down (potion icons), negative slides up (open chat).
	 */
	public void setTargetPush(float push) {
		this.targetPushY = push;
	}

	/** Accumulates onto the desired slide, so independent avoidance passes combine. */
	public void addTargetPush(float delta) {
		this.targetPushY += delta;
	}

	/** Whether the widget draws right now (its HUD toggle is on etc.). */
	public abstract boolean isVisible();

	/**
	 * True when {@link #draw} reads the player or the world, and so cannot run
	 * with no world loaded. The editor draws a name placeholder for these instead
	 * of their real content, which is what lets it open from the main menu.
	 */
	public boolean requiresPlayer() {
		return true;
	}

	/** Resolve avoidance and transition state once before blur bounds are registered. */
	final void prepareFrame(int screenWidth, int screenHeight, boolean editing) {
		if (Double.isNaN(fracX)) applyDefaultPosition();
		updateVisibility(editing);
		absX = anchoredLeft(screenWidth);
		absY = anchoredTop(screenHeight) + Math.round(easePush());
		anchorRight = absX + lastWidth / 2 > screenWidth / 2;
		anchorBottom = absY + lastHeight / 2 > screenHeight / 2;
		frameTransitionScale = animation.is("Scale") ? Math.max(visibility, 0.01f) : 1.0f;
		float slide = animation.is("Slide") ? 1.0f - visibility : 0.0f;
		frameSlideX = switch (animationDirection.get()) {
			case "Left" -> -getWidth() * slide;
			case "Right" -> getWidth() * slide;
			default -> 0.0f;
		};
		frameSlideY = switch (animationDirection.get()) {
			case "Up" -> -getHeight() * slide;
			case "Down" -> getHeight() * slide;
			default -> 0.0f;
		};
		frameOpacity = opacity.getFloat() / 100.0f * (animation.is("Off") ? 1.0f : visibility);
		framePrepared = true;
	}

	final boolean preparedVisible(boolean editing) { return editing || visibility > 0.001f; }
	final int visualLeft() { return absX + Math.round(frameSlideX); }
	final int visualTop() { return absY + Math.round(frameSlideY); }
	final int visualWidth() { return Math.max(1, Math.round(getWidth() * frameTransitionScale)); }
	final int visualHeight() { return Math.max(1, Math.round(getHeight() * frameTransitionScale)); }

	public final void render(GuiGraphicsExtractor g, boolean editing) {
		if (!framePrepared) prepareFrame(g.guiWidth(), g.guiHeight(), editing);
		framePrepared = false;
		if (!editing && visibility <= 0.001f) {
			return;
		}
		// resolve which edge we hug from last frame's center — content justifies toward it
		float scale = effectiveWidgetScale() * frameTransitionScale;
		int pad = padding.getInt();
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(frameSlideX, frameSlideY);
		pose.translate(absX, absY);
		pose.scale(scale, scale);
		pose.translate(-absX, -absY);
		pose.translate(pad, pad);
		ACTIVE_STYLE.set(this);
		sharedPanelClaimed = false;
		try {
			if (!backgroundMode.is("Widget") || !borderMode.is("Widget")) {
				// Explicit shared styling wraps the complete widget, including widgets
				// that normally have no panel of their own.
				sharedWrapperPass = true;
				try {
					unlucky.utility.client.util.Render2D.hudPanel(g, absX, absY,
							Math.max(lastWidth, 1), Math.max(lastHeight, 1),
							backgroundMode.is("Widget") ? 0 : Theme.hudBackground);
				} finally {
					sharedWrapperPass = false;
				}
			}
			if (requiresPlayer() && (mc().player == null || mc().level == null)) {
				if (editing) drawPlaceholder(g);
			} else {
				draw(g, editing);
			}
		} finally {
			ACTIVE_STYLE.remove();
			pose.popMatrix();
		}
	}

	private void updateVisibility(boolean editing) {
		long now = System.nanoTime();
		float dt = lastVisibilityNanos == 0L ? 0.0f : Math.min((now - lastVisibilityNanos) / 1.0e9f, 0.1f);
		lastVisibilityNanos = now;
		float target = editing || isVisible() ? 1.0f : 0.0f;
		if (animation.is("Off")) {
			visibility = target;
			return;
		}
		float step = dt * (4.0f + animationSpeed.getFloat() * 8.0f);
		visibility = target > visibility ? Math.min(target, visibility + step) : Math.max(target, visibility - step);
	}

	private int anchoredLeft(int screenWidth) {
		int margin = anchor.is("Free") ? MARGIN : anchorMargin.getInt();
		int available = Math.max(screenWidth - getWidth() - 2 * margin, 0);
		return switch (anchor.get()) {
			case "Top", "Center", "Bottom" -> margin + available / 2;
			case "Top right", "Right", "Bottom right" -> margin + available;
			default -> margin + (int) Math.round(getFracX() * available);
		};
	}

	private int anchoredTop(int screenHeight) {
		int margin = anchor.is("Free") ? MARGIN : anchorMargin.getInt();
		int available = Math.max(screenHeight - getHeight() - 2 * margin, 0);
		return switch (anchor.get()) {
			case "Left", "Center", "Right" -> margin + available / 2;
			case "Bottom left", "Bottom", "Bottom right" -> margin + available;
			default -> margin + (int) Math.round(getFracY() * available);
		};
	}

	/** Stand-in shown in the editor with no world: just the name, still draggable. */
	private void drawPlaceholder(GuiGraphicsExtractor g) {
		int w = Math.max(lastWidth, unlucky.utility.client.util.Render2D.width(name) + 10);
		int h = Math.max(lastHeight, 14);
		setSize(w, h);
		unlucky.utility.client.util.Render2D.roundedRect(g, absX, absY, w, h, 3, 0x50000000);
		unlucky.utility.client.util.Render2D.textNoShadow(g, name, absX + 5, absY + (h - 8) / 2,
				unlucky.utility.client.ui.Theme.textDim);
	}

	/** Advances {@link #pushY} toward the requested target with frame-rate-independent easing. */
	private float easePush() {
		long now = System.nanoTime();
		float dt = lastPushNanos == 0L ? 0f : Math.min((now - lastPushNanos) / 1.0e9f, 0.1f);
		lastPushNanos = now;
		pushY += (targetPushY - pushY) * (1f - (float) Math.exp(-14f * dt));
		if (Math.abs(targetPushY - pushY) < 0.4f) {
			pushY = targetPushY;
		}
		return pushY;
	}

	/** True when the widget hugs the right half of the screen (justify content right). */
	protected boolean anchorRight() {
		return anchorRight;
	}

	/** True when the widget hugs the bottom half of the screen (stack content upward). */
	protected boolean anchorBottom() {
		return anchorBottom;
	}

	/** X for a line of the given width so it justifies toward the hugged edge. */
	protected int alignedX(int lineWidth, int pad) {
		return switch (contentAlignment.get()) {
			case "Left" -> absX + pad;
			case "Center" -> absX + (lastWidth - lineWidth) / 2;
			case "Right" -> absX + lastWidth - pad - lineWidth;
			default -> anchorRight ? absX + lastWidth - pad - lineWidth : absX + pad;
		};
	}

	/* Shared-style accessors consumed by Render2D and individual widgets. */
	public static HudWidget activeStyle() { return ACTIVE_STYLE.get(); }
	public float styleOpacity() { return frameOpacity; }
	public float textScale() { return textScale.getFloat(); }
	public String textStyle() { return textStyle.get(); }
	public String styleText(String text) {
		String shown = uppercase.get() ? text.toUpperCase(java.util.Locale.ROOT) : text;
		return unlucky.utility.client.util.ChatFont.apply(shown, fontStyle.get());
	}
	public int extraPadding() { return padding.getInt(); }
	public int cornerRadius() { return cornerRadius.getInt(); }
	public String backgroundMode() { return backgroundMode.get(); }
	public String borderMode() { return borderMode.get(); }
	/** True when shared chrome replaces a widget's own direct background. */
	public boolean hasExplicitBackgroundOverride() { return !backgroundMode.is("Widget"); }
	/** True when one shared wrapper owns the complete widget's background or border. */
	public boolean hasExplicitPanelOverride() {
		return !backgroundMode.is("Widget") || !borderMode.is("Widget");
	}
	/** True only while the single complete-widget chrome wrapper is being drawn. */
	public boolean isSharedWrapperPass() { return sharedWrapperPass; }
	/** Minimum row height that cannot overlap text after per-widget text scaling. */
	protected int styledLineHeight(int normalHeight) {
		return Math.max(normalHeight,
				(int) Math.ceil(unlucky.utility.client.util.Render2D.FONT_HEIGHT * textScale()) + 2);
	}
	/** Returns true only for the first shared-panel draw in an explicit override mode. */
	public boolean claimSharedPanel() {
		if (backgroundMode.is("Widget") && borderMode.is("Widget")) return true;
		// A border-only override must keep each widget's normal background while the
		// shared wrapper owns the one outer border.
		if (!sharedWrapperPass && backgroundMode.is("Widget")) return true;
		if (!sharedWrapperPass) return false;
		if (sharedPanelClaimed) return false;
		sharedPanelClaimed = true;
		return true;
	}
	public boolean usesBlurBackground() { return backgroundMode.is("Blur"); }
	public boolean compactLayout() { return density.is("Compact"); }
	public boolean horizontalLayout() { return orientation.is("Horizontal"); }
	public boolean verticalLayout() { return orientation.is("Vertical"); }

	public int styleColor(int color) {
		return ColorUtil.multiplyAlpha(color, frameOpacity);
	}

	public int accentAt(int coordinate, int extent) {
		int color = switch (accentMode.get()) {
			case "Category" -> categoryAccent();
			case "RGB" -> java.awt.Color.HSBtoRGB(((System.currentTimeMillis() * rgbSpeed.getFloat() / 6000.0f)
					+ coordinate / (float) Math.max(extent, 1)) % 1.0f, 0.78f, 1.0f) | 0xFF000000;
			case "Static" -> accentColor.get();
			case "Gradient" -> ColorUtil.lerp(accentColor.get(), accentColor2.get(),
					Math.clamp(coordinate / (float) Math.max(extent, 1), 0.0f, 1.0f));
			default -> Theme.hudScreenAccentY(coordinate, extent);
		};
		// Render2D applies the active widget opacity at the final draw site. Returning
		// an already-faded value here made callers such as gradientText apply opacity
		// twice, especially visible on translucent widgets.
		return color;
	}

	private int categoryAccent() {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("target") || lower.contains("pop") || lower.contains("armor")) return 0xFFFF5D67;
		if (lower.contains("speed") || lower.contains("coord") || lower.contains("key")) return 0xFF58A6FF;
		if (lower.contains("radar") || lower.contains("layer") || lower.contains("material")) return 0xFFB47CFF;
		if (lower.contains("potion") || lower.contains("brew") || lower.contains("item")) return 0xFF55DDAA;
		return 0xFFFFC857;
	}

	/** A line of HUD text with its color, for size-sorted vertical stacks. */
	public record TextLine(String text, int color) {
	}

	/**
	 * Orders stacked lines by width so the block fans out from its docked corner:
	 * the widest line sits nearest the vertical edge the widget hugs (the bottom
	 * when docked low, the top when docked high), the narrowest farthest from it.
	 */
	protected <T> void sortBySize(java.util.List<T> lines, java.util.function.ToIntFunction<T> width) {
		lines.sort(java.util.Comparator.comparingInt(width)); // narrowest first
		if (!anchorBottom) {
			java.util.Collections.reverse(lines); // docked high: widest on top
		}
	}

	protected abstract void draw(GuiGraphicsExtractor g, boolean editing);

	/** Sets the default fractional position, e.g. (0, 0) top left, (1, 0) top right. */
	protected abstract void applyDefaultPosition();

	/** True when the widget's center sits in the right half of the screen. */
	protected boolean alignsRight(int screenWidth) {
		return absX + lastWidth / 2 > screenWidth / 2;
	}
}
