package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.GearUtil;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Vanilla-look player name tags with maximum information, drawn as screen-space
 * billboards through the same 2D projection pass PlayerESP uses.
 *
 * <p>The vanilla tag for players is cancelled in {@code EntityRendererMixin} (see
 * {@link #hidesVanilla(Entity)}) so ours replaces it cleanly instead of doubling
 * up. Everything — text, hearts and item icons — is drawn inside one scaled pose
 * anchored just above the player's head, so a single {@code scale} plus the
 * optional distance falloff sizes the whole tag at once.
 *
 * <p>Split tick/frame (Phase 10 Tier 2): target selection (including the
 * line-of-sight raycast) and all tag content — strings, colors, enchant chips and
 * their font widths — are built once per tick in {@link #onTick} and cached as
 * {@link Tag}s; the per-frame path is just projection and draw calls.
 */
public class NameTags extends Module {
	private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");

	public final NumberSetting range = add(new NumberSetting("Range", "Max tag distance", 128, 16, 256, 8));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Show your own tag in third person / freecam", false));
	public final NumberSetting scale = add(new NumberSetting("Scale", "Overall tag size", 1.0, 0.5, 2.0, 0.1));
	public final BooleanSetting constantSize = add(new BooleanSetting("Constant size", "Stay readable regardless of distance", true));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls", "Show tags of players behind blocks", true));
	public final ColorSetting nameColor = add(new ColorSetting("Name color", "Player name color (friend color later)", 0xFFF2F2F2));
	public final BooleanSetting gamemode = add(new BooleanSetting("Gamemode", "Prefix a letter for the player's gamemode", true));
	public final ModeSetting health = add(new ModeSetting("Health", "How to show health", "Number", "Off", "Number", "Hearts"));
	public final BooleanSetting ping = add(new BooleanSetting("Ping", "Latency in ms", true));
	public final BooleanSetting distance = add(new BooleanSetting("Distance", "Distance in metres", true));
	public final BooleanSetting armor = add(new BooleanSetting("Armor", "Row of worn gear above the name", true));
	public final BooleanSetting enchants = add(new BooleanSetting("Enchants", "Stack enchant tags above each gear icon", false));
	public final NumberSetting enchantLimit = add(new NumberSetting("Enchant limit", "Max enchant lines per item", 10, 5, 45, 1));
	public final ModeSetting bgStyle = add(new ModeSetting("Background", "Name plate backdrop", "Vanilla", "Off", "Custom", "Vanilla"));
	public final NumberSetting bgOpacity = add(new NumberSetting("Custom opacity", "Backdrop opacity for the Custom style", 160, 0, 255, 5));
	public final BooleanSetting hideVanilla = add(new BooleanSetting("Hide vanilla", "Cancel the built-in name tag so it doesn't double up", true));
	public final BooleanSetting scoreboard = add(new BooleanSetting("Scoreboard", "Below-name objective as a row in our style", true));
	public final BooleanSetting unluckyMark = add(new BooleanSetting("Unlucky mark", "Star after the name for Unlucky users", true));

	/** One tick's worth of tag content for one player; font widths pre-measured. */
	private static final class Tag {
		final AbstractClientPlayer player;
		final List<Seg> segs = new ArrayList<>(5);
		int totalWidth;
		List<ItemStack> gear = List.of();
		final List<List<Seg>> chips = new ArrayList<>(6); // parallel to gear
		int maxChipW;
		boolean anyChips;
		String score; // below_name objective line, e.g. "6 Deaths"
		int scoreWidth;

		Tag(AbstractClientPlayer player) {
			this.player = player;
		}
	}

	private final List<Tag> tags = new ArrayList<>();

	public NameTags() {
		super("NameTags", "Rich player name tags", Category.RENDER);
	}

	/**
	 * Whether the vanilla tag for {@code entity} should be suppressed this frame —
	 * called from {@code EntityRendererMixin} at name-tag extract time. Only players
	 * within range are hidden, so distant players (which vanilla wouldn't tag anyway)
	 * keep whatever vanilla decides.
	 */
	public static boolean hidesVanilla(Entity entity) {
		NameTags module = UnluckyClient.INSTANCE.modules.get(NameTags.class);
		if (!module.isEnabled() || !module.hideVanilla.get() || !(entity instanceof AbstractClientPlayer player)) {
			return false;
		}
		var self = net.minecraft.client.Minecraft.getInstance().player;
		if (self == null || player == self) {
			return false;
		}
		return player.distanceTo(self) <= module.range.get();
	}

	private List<AbstractClientPlayer> targets() {
		List<AbstractClientPlayer> result = new ArrayList<>();
		if (mc().level == null || mc().player == null) {
			return result;
		}
		boolean detached = mc().gameRenderer.mainCamera().isDetached();
		for (AbstractClientPlayer player : mc().level.players()) {
			if (player == mc().player) {
				if (self.get() && detached) {
					result.add(player);
				}
			} else if (player.distanceTo(mc().player) <= range.get() && !player.isInvisibleTo(mc().player)) {
				if (throughWalls.get() || mc().player.hasLineOfSight(player)) {
					result.add(player);
				}
			}
		}
		// draw far tags first so nearer ones land on top
		result.sort(Comparator.comparingDouble((AbstractClientPlayer p) -> p.distanceToSqr(mc().player)).reversed());
		return result;
	}

	@Override
	protected void onDisable() {
		tags.clear();
	}

	/** Rebuilds the tag cache: selection, raycasts, strings and widths — all tick work. */
	@Override
	public void onTick() {
		tags.clear();
		for (AbstractClientPlayer player : targets()) {
			tags.add(buildTag(player));
		}
	}

	private Tag buildTag(AbstractClientPlayer player) {
		Tag tag = new Tag(player);
		PlayerInfo info = mc().getConnection() != null ? mc().getConnection().getPlayerInfo(player.getUUID()) : null;
		if (gamemode.get() && info != null) {
			addSeg(tag, gameModeLabel(info.getGameMode()) + " ", 0xFFB9C6FF);
		}
		int dot = UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.misc.Friends.class)
				.nametagDotColor(player.getUUID());
		if (dot != 0) {
			addSeg(tag, unlucky.utility.client.util.FriendManager.DOT + " ", dot);
		}
		addSeg(tag, player.getName().getString(), nameColor.get());
		if (unluckyMark.get()) {
			// their own registered color, same as the tab list — trails the name
			int mark = UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.misc.UnluckyUsers.class)
					.markerFor(player.getUUID());
			if (mark != 0) {
				addSeg(tag, " ✦", mark);
			}
		}
		if (health.is("Number")) {
			float frac = Mth.clamp(player.getHealth() / player.getMaxHealth(), 0.0f, 1.0f);
			addSeg(tag, " " + (int) Math.ceil(player.getHealth()), ColorUtil.lerp(0xFFFF5555, 0xFF55FF55, frac));
		}
		if (ping.get()) {
			int p = info != null ? info.getLatency() : -1;
			addSeg(tag, "  " + (p < 0 ? "?" : p + "ms"), pingColor(p));
		}
		if (distance.get()) {
			addSeg(tag, "  " + (int) player.distanceTo(mc().player) + "m", 0xFFBFC4CC);
		}
		if (scoreboard.get()) {
			// vanilla's own ready-made "<score> <objective>" line; null when no
			// below_name objective is displayed — the vanilla render of it is
			// suppressed alongside the tag in EntityRendererMixin
			var below = player.belowNameDisplay();
			if (below != null) {
				tag.score = below.getString();
				tag.scoreWidth = Render2D.width(tag.score);
			}
		}
		if (armor.get()) {
			tag.gear = GearUtil.gear(player);
			if (enchants.get()) {
				int limit = enchantLimit.getInt(); // per item, so one god-piece can't starve the rest
				for (ItemStack stack : tag.gear) {
					List<String> full = GearUtil.enchantChips(stack, 3, false);
					List<String> capped = full.size() > limit ? full.subList(0, limit) : full;
					List<Seg> row = new ArrayList<>(capped.size());
					for (String chip : capped) {
						int w = Render2D.width(chip);
						tag.anyChips = true;
						tag.maxChipW = Math.max(tag.maxChipW, w);
						row.add(new Seg(chip, 0xFFB9F2C0, w));
					}
					tag.chips.add(row);
				}
			}
		}
		return tag;
	}

	private void addSeg(Tag tag, String text, int color) {
		int width = Render2D.width(text);
		tag.segs.add(new Seg(text, color, width));
		tag.totalWidth += width;
	}

	/** Called from the HUD layer every frame — including while the module is off, so gate here. */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!isEnabled() || mc().level == null || mc().player == null) {
			return;
		}
		int guiWidth = g.guiWidth();
		int guiHeight = g.guiHeight();
		for (Tag tag : tags) {
			AbstractClientPlayer player = tag.player;
			if (player.isRemoved()) {
				continue; // logged off since the cache tick
			}
			Vec3 feet = player.getPosition(partialTick);
			Vec3 head = feet.add(0, player.getBbHeight() + 0.5, 0);
			Vec3 screen = Render3D.worldToScreen(head, guiWidth, guiHeight);
			if (screen == null) {
				continue;
			}
			float dist = player.distanceTo(mc().player);
			float distScale = constantSize.get() ? 1.0f : Mth.clamp(12.0f / Math.max(dist, 1.0f), 0.35f, 1.5f);
			float s = scale.getFloat() * distScale;

			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate((int) Math.round(screen.x), (int) Math.round(screen.y));
			pose.scale(s, s);
			drawTag(g, tag);
			pose.popMatrix();
		}
	}

	/** Draws the whole tag in local coordinates: anchor (0,0) sits just above the head, stacking upward. */
	private void drawTag(GuiGraphicsExtractor g, Tag tag) {
		int total = tag.totalWidth;
		int nameTop = -Render2D.FONT_HEIGHT;
		int startX = -Math.round(total / 2.0f); // symmetric about the head anchor at x=0

		backdrop(g, startX, nameTop, total);
		int x = startX;
		for (Seg seg : tag.segs) {
			Render2D.text(g, seg.text(), x, nameTop, seg.color());
			x += seg.width();
		}

		if (tag.score != null) {
			// below the name like vanilla's below_name slot, but our scale and
			// backdrop, packed tight instead of vanilla's floaty full-size line
			int scoreTop = 1;
			int scoreX = -Math.round(tag.scoreWidth / 2.0f);
			backdrop(g, scoreX, scoreTop, tag.scoreWidth);
			Render2D.text(g, tag.score, scoreX, scoreTop, 0xFFD8DEE6);
		}

		int cursorY = nameTop - 2; // bottom edge of the next row up

		if (health.is("Hearts")) {
			// scale the heart row to span the name line under it, so it reads as one unit
			int drawn = drawHearts(g, tag.player.getHealth(), tag.player.getMaxHealth(), cursorY, total);
			cursorY -= drawn + 2;
		}

		if (armor.get() && !tag.gear.isEmpty()) {
			drawGear(g, tag, cursorY);
		}
	}

	/** One text row's plate in the configured style; {@code (x, top)} = text origin. */
	private void backdrop(GuiGraphicsExtractor g, int x, int top, int width) {
		switch (bgStyle.get()) {
			case "Custom" -> Render2D.roundedRect(g, x - 2, top - 1, width + 4, Render2D.FONT_HEIGHT + 2, 2,
					ColorUtil.withAlpha(0x000000, bgOpacity.getInt()));
			case "Vanilla" -> {
				// same flat backdrop vanilla uses: options text-background opacity, 1px pad
				int alpha = (int) (mc().options.getBackgroundOpacity(0.25f) * 255.0f);
				Render2D.rect(g, x - 1, top - 1, width + 2, Render2D.FONT_HEIGHT + 1,
						ColorUtil.withAlpha(0x000000, alpha));
			}
			default -> { }
		}
	}

	/**
	 * Draws the gear row as an even grid: every column is the same width (the widest
	 * enchant chip across all items, plus a gap), so the (compact, 3-letter) enchant
	 * chips sit in tidy, well-separated columns above their icon instead of a ragged
	 * jumble. Chips are bottom-aligned to the icon row; total lines are capped by the
	 * Enchant-limit slider. With no enchants the icons pack tight at 16px.
	 */
	private void drawGear(GuiGraphicsExtractor g, Tag tag, int cursorY) {
		int icon = 16;
		int colW = tag.anyChips ? Math.max(icon, tag.maxChipW) + 4 : icon; // uniform column + gap
		int rowW = colW * tag.gear.size();
		int armorTop = cursorY - icon;
		int cx = -Math.round(rowW / 2.0f);
		for (int i = 0; i < tag.gear.size(); i++) {
			int colCenter = cx + colW / 2;
			List<Seg> chips = i < tag.chips.size() ? tag.chips.get(i) : List.of();
			int cy = armorTop - 1 - chips.size() * Render2D.FONT_HEIGHT;
			for (Seg chip : chips) {
				Render2D.text(g, chip.text(), colCenter - chip.width() / 2, cy, chip.color());
				cy += Render2D.FONT_HEIGHT;
			}
			g.item(tag.gear.get(i), colCenter - icon / 2, armorTop);
			cx += colW;
		}
	}

	/**
	 * Draws the heart row bottom-anchored at {@code bottom}, uniformly scaled so its
	 * total width matches {@code targetWidth} (the name line) within a sane clamp, and
	 * returns the drawn height so the caller can stack the next row above it.
	 */
	private int drawHearts(GuiGraphicsExtractor g, float hp, float maxHp, int bottom, int targetWidth) {
		int containers = Math.min((int) Math.ceil(maxHp / 2.0f), 10);
		if (containers <= 0) {
			return 0;
		}
		float natural = (containers - 1) * 8.0f + 9.0f;
		float s = targetWidth > 0 ? Mth.clamp(targetWidth / natural, 0.6f, 1.4f) : 1.0f;
		int size = Math.round(9.0f * s);
		float step = 8.0f * s;
		float x0 = -((containers - 1) * step + size) / 2.0f;
		int top = bottom - size;
		for (int i = 0; i < containers; i++) {
			int x = Math.round(x0 + i * step);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, x, top, size, size);
			float h = hp - i * 2.0f;
			if (h >= 2.0f) {
				g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, x, top, size, size);
			} else if (h >= 1.0f) {
				g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, x, top, size, size);
			}
		}
		return size;
	}

	private static String gameModeLabel(GameType mode) {
		if (mode == null) {
			return "?";
		}
		return switch (mode) {
			case CREATIVE -> "C";
			case ADVENTURE -> "A";
			case SPECTATOR -> "SP";
			default -> "S";
		};
	}

	private static int pingColor(int ping) {
		if (ping < 0) {
			return 0xFF9AA0A8;
		}
		if (ping < 80) {
			return 0xFF55FF55;
		}
		if (ping < 150) {
			return 0xFFB9F24A;
		}
		if (ping < 300) {
			return 0xFFFFC24A;
		}
		return 0xFFFF5555;
	}

	private record Seg(String text, int color, int width) {
	}
}
