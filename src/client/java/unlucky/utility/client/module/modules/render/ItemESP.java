package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Labels and tracers for dropped items.
 *
 * <p><b>It does not draw a silhouette, and that is the design.</b> {@link Shader} owns the one
 * outline mask the client has — one framebuffer, one post chain, one composite — and a second
 * item pass would not be a second look, it would be the same pixels drawn twice with whichever
 * pass ran last winning. What this module does instead is <em>annotate</em>: {@code Silhouette}
 * tells Shader which items are interesting and what colour they are, through
 * {@link #silhouetteColor}, so an item that has a label also has an outline and one that was
 * filtered out has neither.
 *
 * <p><b>One filter, three consumers.</b> The label, the tracer and the delegated silhouette all
 * come from {@link #wanted}. That is worth stating because the alternative is invisible: a
 * whitelist applied to labels but not to tracers produces lines leading to nothing, and nobody
 * reads that as a bug in a filter.
 *
 * <p><b>The dropped-item model is ItemPhysics's.</b> This module never touches how an item is
 * oriented or bobs, only what is drawn on top of it.
 */
public class ItemESP extends Module {
	/** Hard ceiling on labelled items. A hopper feed can put hundreds in one place. */
	private static final int MAX_TARGETS = 128;

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"What to draw", "Labels", "Labels", "Tracers", "Labels and tracers"));
	public final ModeSetting filter = add(new ModeSetting("Filter",
			"Which items count", "All", "All", "Whitelist", "Blacklist"));
	public final ItemListSetting items = add(new ItemListSetting("Items",
			"Used by Whitelist and Blacklist — right-click to pick", item -> true),
			() -> !filter.is("All"));

	public final NumberSetting range = add(new NumberSetting("Range",
			"How far out items are considered", 128, 8, 512, 8));
	public final NumberSetting minimumAge = add(new NumberSetting("Minimum age",
			"Ticks before a fresh drop is shown; hides your own mining spray", 0, 0, 200, 5));

	public final BooleanSetting showName = add(new BooleanSetting("Show name",
			"Item display name", true));
	public final BooleanSetting showCount = add(new BooleanSetting("Show count",
			"Stack count", true));
	public final BooleanSetting showDistance = add(new BooleanSetting("Show distance",
			"Distance suffix", false));
	public final BooleanSetting showAge = add(new BooleanSetting("Show age",
			"Seconds since it dropped", false));
	public final BooleanSetting textShadow = add(new BooleanSetting("Text shadow",
			"Drop shadow behind the label", true));
	public final NumberSetting textScale = add(new NumberSetting("Text scale",
			"Label size", 1.0, 0.5, 2.0, 0.1));

	public final ModeSetting colorMode = add(new ModeSetting("Color mode",
			"Rarity uses the item's own rarity colour; Item gives every kind its own stable hue",
			"Rarity", "Fixed", "Rarity", "Item", "Theme"));
	public final ColorSetting fixedColor = add(new ColorSetting("Fixed color",
			"Used by Fixed and Theme", 0xFFFFD966),
			() -> colorMode.is("Fixed") || colorMode.is("Theme"));

	public final ModeSetting tracerOrigin = add(new ModeSetting("Tracer origin",
			"Where tracer lines start", "Bottom", "Bottom", "Crosshair"));
	public final NumberSetting tracerWidth = add(new NumberSetting("Tracer width",
			"Tracer line width", 1.0, 0.5, 5.0, 0.1));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw labels and tracers for items behind terrain", true));
	public final BooleanSetting silhouette = add(new BooleanSetting("Silhouette",
			"Hand matching items to Shader's outline pass. Needs Shader enabled — this module "
					+ "annotates that pass, it does not own one.", false));

	/** One item worth drawing, resolved on tick and drawn every frame until the next one. */
	private record Target(ItemEntity entity, int color, String label) {
	}

	private final List<Target> cached = new ArrayList<>();
	/** Three, not two: {@code worldToScreen} writes {x, y, depth}. */
	private final double[] proj = new double[3];

	public ItemESP() {
		super("ItemESP", "Labels and tracers for dropped items", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onDisable() {
		cached.clear();
	}

	@Override
	public void onTick() {
		cached.clear();
		if (mc().level == null || mc().player == null) {
			return;
		}
		double limit = range.get() * range.get();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity item)) {
				continue;
			}
			if (mc().player.distanceToSqr(item) > limit || !wanted(item)) {
				continue;
			}
			cached.add(new Target(item, colorFor(item.getItem()), label(item)));
			if (cached.size() >= MAX_TARGETS) {
				// The cap is on the work, not on the draw: past this many, another label is not
				// information, it is a wall of overlapping text on top of the same pile.
				break;
			}
		}
	}

	/**
	 * The one filter. Also the answer Shader gets, which is what keeps the three views agreeing.
	 *
	 * <p>Age is checked here rather than at draw time so an item that is still too young does not
	 * get a tracer while its label waits.
	 */
	private boolean wanted(ItemEntity item) {
		ItemStack stack = item.getItem();
		if (stack.isEmpty() || item.getAge() < minimumAge.getInt()) {
			return false;
		}
		return switch (filter.get()) {
			case "Whitelist" -> items.contains(stack.getItem());
			case "Blacklist" -> !items.contains(stack.getItem());
			default -> true;
		};
	}

	/**
	 * Shader's question: what colour should this item's silhouette be, or 0 for "not mine".
	 *
	 * <p>Deliberately answers 0 whenever the toggle is off, so an ItemESP with Silhouette off
	 * leaves Shader's own {@code Items} switch to mean exactly what it always meant.
	 */
	public int silhouetteColor(ItemEntity item) {
		if (!isEnabled() || !silhouette.get() || mc().player == null || item == null) {
			return 0;
		}
		if (mc().player.distanceToSqr(item) > range.get() * range.get() || !wanted(item)) {
			return 0;
		}
		return colorFor(item.getItem());
	}

	/**
	 * Colour for a stack.
	 *
	 * <p>Item mode hashes the registry id into a hue rather than sampling the texture: a stable
	 * per-kind colour is the useful property — the same ore always looks the same — and reading
	 * pixels out of the atlas to average them would be an expensive way to arrive at brown.
	 */
	private int colorFor(ItemStack stack) {
		return switch (colorMode.get()) {
			case "Fixed", "Theme" -> fixedColor.get();
			case "Item" -> {
				int hash = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().hashCode();
				yield ColorUtil.hsb((hash & 0xFFFF) / 65535.0f, 0.65f, 1.0f, 255);
			}
			// Rarity carries a ChatFormatting, which is a text style rather than a number; the
			// legacy-format table is where 26.2 keeps the actual RGB for one.
			default -> 0xFF000000
					| (TextColor.fromLegacyFormat(stack.getRarity().color()).getValue() & 0xFFFFFF);
		};
	}

	private String label(ItemEntity item) {
		ItemStack stack = item.getItem();
		StringBuilder text = new StringBuilder();
		if (showName.get()) {
			text.append(stack.getHoverName().getString());
		}
		if (showCount.get() && stack.getCount() > 1) {
			text.append(text.isEmpty() ? "" : " ").append('x').append(stack.getCount());
		}
		if (showDistance.get() && mc().player != null) {
			text.append(text.isEmpty() ? "" : " ")
					.append('[').append((int) mc().player.distanceTo(item)).append("m]");
		}
		if (showAge.get()) {
			text.append(text.isEmpty() ? "" : " ").append('(').append(item.getAge() / 20).append("s)");
		}
		return text.toString();
	}

	/** Called from the HUD layer every frame — including while off, so gate here. */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!isEnabled() || cached.isEmpty() || mc().player == null) {
			return;
		}
		boolean labels = !mode.is("Tracers");
		boolean tracers = !mode.is("Labels");
		if (!labels && !tracers) {
			return;
		}
		int guiWidth = g.guiWidth();
		int guiHeight = g.guiHeight();
		float originX = guiWidth / 2.0f;
		float originY = tracerOrigin.is("Crosshair") ? guiHeight / 2.0f : guiHeight;

		for (Target target : cached) {
			ItemEntity item = target.entity();
			if (item.isRemoved()) {
				continue; // picked up since the cache tick
			}
			// A screen-space overlay has no depth buffer to test against, so "through walls" has
			// to be asked as a question rather than left to the pipeline: without this the switch
			// would silently do nothing and every item would show through terrain.
			if (!throughWalls.get() && !mc().player.hasLineOfSight(item)) {
				continue;
			}
			Vec3 at = item.getPosition(partialTick);
			if (!Render3D.worldToScreen(at.x, at.y + 0.4, at.z, guiWidth, guiHeight, proj)) {
				continue;
			}
			int x = (int) proj[0];
			int y = (int) proj[1];
			if (tracers) {
				Render2D.line(g, originX, originY, (float) proj[0], (float) proj[1],
						tracerWidth.getFloat(), target.color());
			}
			if (labels && !target.label().isEmpty()) {
				drawLabel(g, target.label(), x, y, target.color());
			}
		}
	}

	/**
	 * Centres the label above the projected point.
	 *
	 * <p>Scaled through the pose stack rather than by picking a bigger font: the font is a fixed
	 * 9px atlas, so a scale setting has to be a transform or it is not a scale setting.
	 */
	private void drawLabel(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		float scale = textScale.getFloat();
		int width = Render2D.width(text);
		if (scale == 1.0f) {
			draw(g, text, x - width / 2, y - Render2D.FONT_HEIGHT, color);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		draw(g, text, -width / 2, -Render2D.FONT_HEIGHT, color);
		g.pose().popMatrix();
	}

	private void draw(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		if (textShadow.get()) {
			Render2D.text(g, text, x, y, color);
		} else {
			Render2D.textNoShadow(g, text, x, y, color);
		}
	}

	/** How many items are currently labelled, for the debug read-out. */
	public int targetCount() {
		return cached.size();
	}
}
