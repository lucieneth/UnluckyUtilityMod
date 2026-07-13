package unlucky.utility.client.module.modules.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.mixin.FoodDataAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Full AppleSkin recreation (squeek502/AppleSkin, 26.2-fabric — behavior and
 * pixel art ported from their source and icons.png):
 * <ul>
 *   <li><b>Saturation</b> — their gold arc sprites over each pip, their exact
 *       buckets (pip fraction &gt;0 / &gt;.25 / &gt;.5 / &ge;1). Saturation
 *       syncs via {@code ClientboundSetHealthPacket}, works on any server.</li>
 *   <li><b>Restore previews</b> — holding food flashes the hunger pips it
 *       would fill, the saturation arcs it would back, and the hearts its
 *       regen would heal ({@link #estimatedHealthIncrement} is their exact
 *       regen simulation). Flash is their triangle wave with dwell.</li>
 *   <li><b>Exhaustion</b> — their dither bar behind the food pips, growing
 *       right-to-left (ratio of 4.0). Vanilla never syncs exhaustion (their
 *       server mod does), so this reads the integrated-server player and is
 *       effectively singleplayer-only; on servers the value stays 0 and
 *       nothing draws.</li>
 *   <li><b>Tooltip</b> — food value line on food tooltips
 *       ({@code FoodTooltipComponent}, wired like the InventoryInfo previews
 *       through {@code ItemStackTooltipMixin} + the Fabric callback).</li>
 * </ul>
 * All sprites live in {@code assets/unlucky/textures/gui/sprites/food/} and
 * stitch into the vanilla GUI atlas, so resource packs restyle them by
 * shipping the same paths.
 */
public class FoodOverlay extends Module {
	private static final Identifier[] SATURATION = {
			Identifier.fromNamespaceAndPath("unlucky", "food/saturation_1"),
			Identifier.fromNamespaceAndPath("unlucky", "food/saturation_2"),
			Identifier.fromNamespaceAndPath("unlucky", "food/saturation_3"),
			Identifier.fromNamespaceAndPath("unlucky", "food/saturation_full")
	};
	private static final Identifier EXHAUSTION = Identifier.fromNamespaceAndPath("unlucky", "food/exhaustion");
	private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
	private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
	private static final Identifier FOOD_FULL_HUNGER = Identifier.withDefaultNamespace("hud/food_full_hunger");
	private static final Identifier FOOD_HALF_HUNGER = Identifier.withDefaultNamespace("hud/food_half_hunger");

	public final BooleanSetting saturation = add(new BooleanSetting("Saturation", "Gold arcs over pips backed by saturation", true));
	public final BooleanSetting preview = add(new BooleanSetting("Restore preview", "Flash the pips and saturation held food would fill", true));
	public final BooleanSetting healthPreview = add(new BooleanSetting("Health preview", "Flash the hearts held food's regen would heal", true));
	public final BooleanSetting previewAlways = add(new BooleanSetting("Show when holding", "Preview whenever holding food, not only when you could eat it", false));
	public final BooleanSetting exhaustion = add(new BooleanSetting("Exhaustion", "Dither bar behind the pips (singleplayer — servers never send it)", true));
	public final BooleanSetting tooltip = add(new BooleanSetting("Tooltip", "Food value line on food tooltips", true));

	public FoodOverlay() {
		super("FoodOverlay", "AppleSkin: saturation, exhaustion, restore previews, food tooltips", Category.RENDER);
	}

	/** ItemStackTooltipMixin: should hovered food stacks grow the tooltip line? */
	public static boolean showTooltip() {
		FoodOverlay module = UnluckyClient.INSTANCE.modules.get(FoodOverlay.class);
		return module.isEnabled() && module.tooltip.get();
	}

	/** HudMixin, extractFood HEAD — the exhaustion bar sits behind the pips. */
	public void drawExhaustion(GuiGraphicsExtractor g, Player player, int y, int rightX) {
		if (!isEnabled() || !exhaustion.get()) {
			return;
		}
		float ratio = Mth.clamp(exhaustionLevel(player) / 4.0f, 0.0f, 1.0f);
		int width = (int) (ratio * 81.0f);
		if (width <= 0) {
			return;
		}
		// right-anchored slice of the 81x9 dither, 75% alpha like AppleSkin
		g.blitSprite(RenderPipelines.GUI_TEXTURED, EXHAUSTION, 81, 9, 81 - width, 0,
				rightX - width, y, width, 9, 0xBFFFFFFF);
	}

	/** HudMixin, extractFood TAIL: same y/rightX vanilla laid the pips with. */
	public void drawOverlay(GuiGraphicsExtractor g, Player player, int y, int rightX) {
		if (!isEnabled()) {
			return;
		}
		FoodData data = player.getFoodData();
		int food = data.getFoodLevel();
		float sat = Math.min(data.getSaturationLevel(), food);
		if (saturation.get()) {
			drawSaturation(g, sat, y, rightX, 1.0f);
		}
		if (preview.get()) {
			FoodProperties held = heldFood(player);
			if (held == null || !previewVisible(held, food)) {
				return;
			}
			int restored = Math.min(20, food + held.nutrition());
			boolean hunger = player.hasEffect(MobEffects.HUNGER);
			float alpha = flashAlpha();
			for (int i = 0; i < 10; i++) {
				int current = Mth.clamp(food - i * 2, 0, 2);
				int after = Mth.clamp(restored - i * 2, 0, 2);
				if (after <= current) {
					continue;
				}
				Identifier sprite = after == 2 ? (hunger ? FOOD_FULL_HUNGER : FOOD_FULL)
						: (hunger ? FOOD_HALF_HUNGER : FOOD_HALF);
				g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, rightX - i * 8 - 9, y, 9, 9, alpha);
			}
			// the saturation those points would be backed by, flashed as arcs
			float restoredSat = Math.min(sat + held.saturation(), restored);
			if (restoredSat > sat) {
				drawSaturation(g, restoredSat, y, rightX, alpha);
			}
		}
	}

	private void drawSaturation(GuiGraphicsExtractor g, float sat, int y, int rightX, float alpha) {
		for (int i = 0; i < 10; i++) {
			// AppleSkin's buckets: fraction of this pip backed by saturation
			float frac = sat / 2.0f - i;
			if (frac <= 0.0f) {
				break;
			}
			Identifier sprite = SATURATION[frac >= 1.0f ? 3 : frac > 0.5f ? 2 : frac > 0.25f ? 1 : 0];
			g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, rightX - i * 8 - 9, y, 9, 9, alpha);
		}
	}

	/** HudMixin, extractHearts TAIL — flash the hearts held food's regen would heal. */
	public void drawHealthPreview(GuiGraphicsExtractor g, Player player, int left, int top,
			int rows, float maxHealth, int health) {
		if (!isEnabled() || !healthPreview.get() || player.getHealth() >= maxHealth) {
			return;
		}
		FoodProperties held = heldFood(player);
		FoodData data = player.getFoodData();
		int food = data.getFoodLevel();
		if (held == null || !previewVisible(held, food)) {
			return;
		}
		int restoredFood = Math.min(20, food + held.nutrition());
		float restoredSat = Math.min(Math.min(data.getSaturationLevel(), food) + held.saturation(), restoredFood);
		float gain = estimatedHealthIncrement(restoredFood, restoredSat, exhaustionLevel(player));
		int modified = (int) Math.ceil(Math.min(health + gain, maxHealth));
		if (modified <= health) {
			return;
		}
		boolean hardcore = player.level().getLevelData().isHardcore();
		Identifier container = Identifier.withDefaultNamespace(hardcore ? "hud/heart/container_hardcore" : "hud/heart/container");
		Identifier full = Identifier.withDefaultNamespace(hardcore ? "hud/heart/hardcore_full" : "hud/heart/full");
		Identifier half = Identifier.withDefaultNamespace(hardcore ? "hud/heart/hardcore_half" : "hud/heart/half");
		float alpha = flashAlpha();
		int rowHeight = Math.max(10 - (rows - 2), 3); // vanilla's stacking formula
		for (int i = 0; i < Mth.ceil(maxHealth / 2.0f); i++) {
			int current = Mth.clamp(health - i * 2, 0, 2);
			int after = Mth.clamp(modified - i * 2, 0, 2);
			if (after <= current) {
				continue;
			}
			int x = left + i % 10 * 8;
			int y = top - i / 10 * rowHeight;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, container, x, y, 9, 9, alpha * 0.25f);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, after == 2 ? full : half, x, y, 9, 9, alpha);
		}
	}

	/**
	 * AppleSkin's exact natural-regen simulation (FoodHelper): while food stays
	 * &ge;18, saturated regen heals sat/6 hp per tick-cycle and each heal costs 6
	 * exhaustion, which drains saturation then food in 4.0 steps — summing until
	 * regen starves out. Estimates total hp the given state will passively heal.
	 */
	public static float estimatedHealthIncrement(int food, float sat, float exhaustion) {
		if (!Float.isFinite(sat) || !Float.isFinite(exhaustion)) {
			return 0.0f;
		}
		float health = 0.0f;
		while (food >= 18) {
			while (exhaustion > 4.0f) {
				exhaustion -= 4.0f;
				if (sat > 0.0f) {
					sat = Math.max(sat - 1.0f, 0.0f);
				} else {
					food -= 1;
				}
			}
			if (food >= 20 && sat > Float.MIN_NORMAL) {
				// fast saturated regen: batch the iterations until exhaustion overflows
				float limited = Math.min(sat, 6.0f);
				float untilOverflow = Math.nextUp(4.0f) - exhaustion;
				int iterations = Math.max(1, (int) Math.ceil(untilOverflow / limited));
				health += limited / 6.0f * iterations;
				exhaustion += limited * iterations;
			} else {
				health += 1.0f;
				exhaustion += 6.0f;
			}
		}
		return health;
	}

	/**
	 * Exhaustion never reaches vanilla clients — read the integrated-server
	 * player when we host the world; elsewhere the client copy just stays 0.
	 */
	private float exhaustionLevel(Player player) {
		var server = mc().getSingleplayerServer();
		if (server != null) {
			var serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
			if (serverPlayer != null) {
				return ((FoodDataAccessor) serverPlayer.getFoodData()).unlucky$exhaustion();
			}
		}
		return ((FoodDataAccessor) player.getFoodData()).unlucky$exhaustion();
	}

	/**
	 * AppleSkin's flash: a triangle wave that overshoots to -0.5..1.5 and clamps,
	 * so the preview dwells briefly at fully-hidden and fully-shown instead of
	 * sine-bouncing. Their step is 0.125/tick → 3.2s full cycle; peak 0.65.
	 */
	private static float flashAlpha() {
		float phase = System.currentTimeMillis() % 3200L / 3200.0f * 4.0f - 0.5f; // -0.5..3.5
		float tri = phase <= 1.5f ? phase : 3.0f - phase; // up to 1.5, back down to -0.5
		return Mth.clamp(tri, 0.0f, 1.0f) * 0.65f;
	}

	/**
	 * AppleSkin only previews food you could actually eat right now (hungry, or
	 * canAlwaysEat like golden apples); "Show when holding" overrides that so a
	 * full player still sees what the item would do.
	 */
	private boolean previewVisible(FoodProperties held, int food) {
		return previewAlways.get() || food < 20 || held.canAlwaysEat();
	}

	static FoodProperties heldFood(Player player) {
		FoodProperties main = player.getMainHandItem().get(DataComponents.FOOD);
		return main != null ? main : player.getOffhandItem().get(DataComponents.FOOD);
	}
}
