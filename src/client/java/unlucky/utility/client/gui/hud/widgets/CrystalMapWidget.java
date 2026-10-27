package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.gui.scene.SceneCanvas;
import unlucky.utility.client.module.modules.combat.Aura;
import unlucky.utility.client.module.modules.combat.CrystalAura;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.HeadRenderer;
import unlucky.utility.client.util.HudEntity;
import unlucky.utility.client.util.Render2D;

/**
 * Target card for crystal fights: the target's model and health on top, and below it a small 3D
 * map of the ground around their feet with every spot a crystal could go.
 *
 * <p>Each spot is a little cube coloured by what a crystal there does — green when it hurts them
 * far more than you, red when you would take as much as they do — and the best few carry the
 * damage number. Players on the map, you included, are drawn as their heads. The map is either a
 * 3D view or a flat top-down grid. The target comes from CrystalAura, then Aura, then your
 * crosshair.
 */
public class CrystalMapWidget extends HudWidget {
	private static final int RADIUS = 3;
	private static final int BELOW = 2;
	private static final int ABOVE = 1;
	private static final long RESCAN_MS = 200;
	private static final int HEADER_H = 34;

	public final BooleanSetting enabled = add(new BooleanSetting("CrystalMap",
			"Target card with a 3D map of crystal spots around them", false));
	public final ModeSetting view = add(new ModeSetting("CrystalMap view",
			"3D blocks, or a flat grid seen from above", "3D", "3D", "Top"));
	public final BooleanSetting backing = add(new BooleanSetting("CrystalMap bg", "Backing behind the card", true));
	public final NumberSetting mapSize = add(new NumberSetting("CrystalMap size", "Width of the card", 130, 90, 220, 5));
	public final NumberSetting labels = add(new NumberSetting("CrystalMap labels",
			"How many of the best spots show their damage", 5, 0, 12, 1));
	public final BooleanSetting followView = add(new BooleanSetting("CrystalMap follow view",
			"Turn the map with your camera, so left on it is left for you (top view turns in quarter steps)", true));

	private record Spot(BlockPos base, float target, float self) {
	}

	private final SceneCanvas canvas = new SceneCanvas();
	private final List<Spot> spots = new ArrayList<>();
	private BlockState[] grid;
	/** Top-view colour of each column, ARGB, 0 when the column is empty. */
	private int[] columns;
	private BlockPos feet;
	private LivingEntity scanned;
	private long scannedAt;

	public CrystalMapWidget() {
		super("CrystalMap");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.72, 0.6);
	}

	private LivingEntity pickTarget() {
		if (CrystalAura.currentTarget != null && CrystalAura.currentTarget.isAlive()) {
			return CrystalAura.currentTarget;
		}
		if (Aura.currentTarget instanceof LivingEntity living && living.isAlive()) {
			return living;
		}
		return mc().crosshairPickEntity instanceof LivingEntity living && living.isAlive() && living != mc().player
				? living : null;
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		LivingEntity target = mc().level == null ? null : pickTarget();
		if (target == null && editing && mc().player != null) {
			target = mc().player; // something to position the card around
		}
		if (target == null) {
			setSize(0, 0);
			return;
		}
		long now = System.currentTimeMillis();
		if (target != scanned || now - scannedAt > RESCAN_MS || !target.blockPosition().equals(feet)) {
			scan(target);
			scannedAt = now;
		}

		int width = mapSize.getInt();
		int mapH = view.is("Top") ? width - 6 : Math.round(width * 0.8f);
		int height = HEADER_H + mapH + 4;
		setSize(width, height);
		int x = getX(), y = getY();
		Render2D.hudPanel(g, x, y, width, height, backing.get());

		// header: model, name, health, armour
		HudEntity.draw(g, target, x + 2, y + 2, x + 28, y + HEADER_H - 2, 20.0f, 0.0f, 0.0f);
		Render2D.text(g, target.getDisplayName().getString(), x + 32, y + 5, Theme.text);
		float health = target.getHealth() + target.getAbsorptionAmount();
		float frac = Math.clamp(target.getHealth() / Math.max(target.getMaxHealth(), 1.0f), 0.0f, 1.0f);
		int barW = width - 38;
		Render2D.rect(g, x + 32, y + 17, barW, 3, 0x60000000);
		Render2D.rect(g, x + 32, y + 17, Math.round(barW * frac), 3, ColorUtil.lerp(0xFFFF5555, 0xFF55FF77, frac));
		String stats = String.format(Locale.ROOT, "%.1f hp  %d armor", health, target.getArmorValue());
		Render2D.text(g, stats, x + 32, y + 23, Theme.textDim);

		if (view.is("Top")) {
			drawTop(g, target, x + 3, y + HEADER_H, width - 6, mapH);
		} else {
			drawMap(g, target, x + 3, y + HEADER_H, width - 6, mapH);
		}
	}

	// ------------------------------------------------------------------ scanning

	private int index(int i, int j, int k) {
		int side = RADIUS * 2 + 1;
		return (i * (BELOW + ABOVE + 1) + j) * side + k;
	}

	private BlockState at(int i, int j, int k) {
		int side = RADIUS * 2 + 1;
		if (i < 0 || k < 0 || j < 0 || i >= side || k >= side || j > BELOW + ABOVE) {
			return null;
		}
		return grid[index(i, j, k)];
	}

	private boolean solid(int i, int j, int k) {
		BlockState state = at(i, j, k);
		return state != null && !state.isAir();
	}

	/** Reads the blocks around the target's feet and scores every place a crystal could go. */
	private void scan(LivingEntity target) {
		scanned = target;
		feet = target.blockPosition();
		int side = RADIUS * 2 + 1;
		grid = new BlockState[side * side * (BELOW + ABOVE + 1)];
		columns = new int[side * side];
		spots.clear();
		for (int i = 0; i < side; i++) {
			for (int j = 0; j <= BELOW + ABOVE; j++) {
				for (int k = 0; k < side; k++) {
					grid[index(i, j, k)] = mc().level.getBlockState(feet.offset(i - RADIUS, j - BELOW, k - RADIUS));
				}
			}
			for (int k = 0; k < side; k++) {
				// highest block in the column, darker the further below their feet it sits
				for (int j = BELOW + ABOVE; j >= 0; j--) {
					BlockState state = grid[index(i, j, k)];
					if (!state.isAir()) {
						BlockPos pos = feet.offset(i - RADIUS, j - BELOW, k - RADIUS);
						int rgb = state.getMapColor(mc().level, pos).col;
						float light = 0.85f - Math.max(BELOW - 1 - j, 0) * 0.2f + Math.max(j - BELOW, 0) * 0.15f;
						columns[i * side + k] = ColorUtil.lerp(0xFF000000, 0xFF000000 | rgb, Math.clamp(light, 0.0f, 1.0f));
						break;
					}
				}
			}
		}
		for (int i = 0; i < side; i++) {
			for (int j = 0; j <= BELOW + ABOVE; j++) {
				for (int k = 0; k < side; k++) {
					BlockState base = grid[index(i, j, k)];
					if (!base.is(Blocks.OBSIDIAN) && !base.is(Blocks.BEDROCK)) {
						continue;
					}
					BlockPos pos = feet.offset(i - RADIUS, j - BELOW, k - RADIUS);
					if (!mc().level.getBlockState(pos.above()).isAir()) {
						continue;
					}
					if (target.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos.above()))) {
						continue; // they are standing in it
					}
					Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
					float damage = ExplosionDamageUtil.damage(centre, ExplosionDamageUtil.CRYSTAL_RADIUS, target);
					float self = target == mc().player ? damage
							: ExplosionDamageUtil.self(centre, ExplosionDamageUtil.CRYSTAL_RADIUS);
					spots.add(new Spot(pos, damage, self));
				}
			}
		}
		spots.sort(Comparator.comparingDouble(spot -> -spot.target()));
	}

	/** Green when a crystal there hurts them far more than you, through yellow, to red when it is even. */
	private static int spotColor(Spot spot) {
		float share = spot.target() / Math.max(spot.target() + spot.self(), 0.01f);
		float t = Math.clamp((share - 0.5f) * 2.0f, 0.0f, 1.0f);
		return t < 0.5f ? ColorUtil.lerp(0xFFFF4040, 0xFFFFD040, t * 2) : ColorUtil.lerp(0xFFFFD040, 0xFF40FF70, t * 2 - 1);
	}

	// ------------------------------------------------------------------ drawing

	private void drawMap(GuiGraphicsExtractor g, LivingEntity target, int x, int y, int width, int height) {
		if (grid == null) {
			return;
		}
		if (followView.get() && mc().player != null) {
			canvas.yaw = 180.0f - mc().player.getYRot();
		}
		canvas.pitch = 42.0f;
		canvas.focusX = target.getX();
		canvas.focusY = feet.getY() - 0.2;
		canvas.focusZ = target.getZ();
		canvas.begin(x, y, width, height, RADIUS + 0.9);

		int side = RADIUS * 2 + 1;
		for (int i = 0; i < side; i++) {
			for (int j = 0; j <= BELOW + ABOVE; j++) {
				for (int k = 0; k < side; k++) {
					BlockState state = grid[index(i, j, k)];
					if (state.isAir()) {
						continue;
					}
					int fi = i, fj = j, fk = k;
					canvas.block(state, feet.getX() + i - RADIUS, feet.getY() + j - BELOW, feet.getZ() + k - RADIUS,
							255, 0xFFFFFFFF,
							direction -> solid(fi + direction.getStepX(), fj + direction.getStepY(), fk + direction.getStepZ()));
				}
			}
		}

		if (!(target instanceof Player)) {
			double tx = target.getX(), ty = target.getY(), tz = target.getZ();
			double halfW = target.getBbWidth() / 2;
			canvas.box(tx - halfW, ty, tz - halfW, tx + halfW, ty + target.getBbHeight(), tz + halfW, 0xFFE04848, Double.NaN);
		}

		for (Spot spot : spots) {
			BlockPos b = spot.base();
			canvas.box(b.getX() + 0.3, b.getY() + 1.0, b.getZ() + 0.3, b.getX() + 0.7, b.getY() + 1.4, b.getZ() + 0.7,
					spotColor(spot), Double.NaN);
		}
		if (!spots.isEmpty()) {
			BlockPos best = spots.getFirst().base();
			canvas.outline(canvas.hull(best.getX(), best.getY(), best.getZ(), best.getX() + 1, best.getY() + 1,
					best.getZ() + 1), Theme.accent2, 1.5f);
		}
		canvas.draw(g);

		int shown = Math.min(labels.getInt(), spots.size());
		for (int n = 0; n < shown; n++) {
			Spot spot = spots.get(n);
			BlockPos b = spot.base();
			String label = String.format(Locale.ROOT, "%.1f", spot.target());
			float lx = canvas.screenX(b.getX() + 0.5, b.getZ() + 0.5);
			float ly = canvas.screenY(b.getX() + 0.5, b.getY() + 1.6, b.getZ() + 0.5);
			if (canvas.contains(lx, ly)) {
				Render2D.text(g, label, Math.round(lx) - Render2D.width(label) / 2, Math.round(ly) - 9, spotColor(spot));
			}
		}

		int size = Math.max(Math.round(canvas.scale() * 0.7f), 6);
		for (Player player : nearbyPlayers()) {
			float hx = canvas.screenX(player.getX(), player.getZ());
			float hy = canvas.screenY(player.getX(), player.getY() + player.getBbHeight() * 0.6, player.getZ());
			if (canvas.contains(hx, hy)) {
				head(g, player, target, Math.round(hx) - size / 2, Math.round(hy) - size / 2, size);
			}
		}
	}

	/** Players standing over the scanned area, the target among them when it is one. */
	private List<Player> nearbyPlayers() {
		List<Player> players = new ArrayList<>();
		for (Player player : mc().level.players()) {
			if (player.isAlive() && Math.abs(player.getX() - (feet.getX() + 0.5)) < RADIUS + 0.5
					&& Math.abs(player.getZ() - (feet.getZ() + 0.5)) < RADIUS + 0.5
					&& Math.abs(player.getY() - feet.getY()) < 4) {
				players.add(player);
			}
		}
		return players;
	}

	/** A player's face, framed red when it is the target and in the accent when it is you. */
	private void head(GuiGraphicsExtractor g, Player player, LivingEntity target, int x, int y, int size) {
		int frame = player == target ? 0xFFE04848 : player == mc().player ? Theme.accent1 : 0xFF000000;
		Render2D.rect(g, x - 1, y - 1, size + 2, size + 2, frame);
		HeadRenderer.draw(g, player.getUUID(), x, y, size);
	}

	/**
	 * Flat map seen from above: each column in the colour of its top block, crystal spots as
	 * squares inside their cell and players as heads. Following your view turns it in quarter
	 * steps so up is roughly ahead of you and the cells stay square.
	 */
	private void drawTop(GuiGraphicsExtractor g, LivingEntity target, int x, int y, int width, int height) {
		if (grid == null) {
			return;
		}
		int side = RADIUS * 2 + 1;
		int cell = Math.max(Math.min(width, height) / side, 4);
		float cx = x + width / 2.0f;
		float cy = y + height / 2.0f;
		// screen up is the way you face, snapped to a quarter turn; north up otherwise
		int quarter = followView.get() && mc().player != null
				? Math.floorMod(Math.round(mc().player.getYRot() / 90.0f), 4) : 2;
		int fx = quarter == 1 ? -1 : quarter == 3 ? 1 : 0;
		int fz = quarter == 0 ? 1 : quarter == 2 ? -1 : 0;
		double ox = feet.getX() + 0.5, oz = feet.getZ() + 0.5;

		for (int i = 0; i < side; i++) {
			for (int k = 0; k < side; k++) {
				int color = columns[i * side + k];
				if (color == 0) {
					continue;
				}
				int dx = i - RADIUS, dz = k - RADIUS;
				int sx = Math.round(cx + (-fz * dx + fx * dz) * cell - cell / 2.0f);
				int sy = Math.round(cy - (fx * dx + fz * dz) * cell - cell / 2.0f);
				Render2D.rect(g, sx, sy, cell - 1, cell - 1, color);
			}
		}

		int inset = cell / 4;
		int shown = Math.min(labels.getInt(), spots.size());
		for (int n = spots.size() - 1; n >= 0; n--) {
			Spot spot = spots.get(n);
			double dx = spot.base().getX() + 0.5 - ox, dz = spot.base().getZ() + 0.5 - oz;
			int sx = Math.round(cx + (float) (-fz * dx + fx * dz) * cell - cell / 2.0f);
			int sy = Math.round(cy - (float) (fx * dx + fz * dz) * cell - cell / 2.0f);
			int color = spotColor(spot);
			if (n == 0) {
				g.outline(sx - 1, sy - 1, cell + 1, cell + 1, Theme.accent2);
			}
			Render2D.rect(g, sx + inset, sy + inset, cell - 1 - inset * 2, cell - 1 - inset * 2, color);
			if (n < shown) {
				String label = String.format(Locale.ROOT, "%.0f", spot.target());
				Render2D.text(g, label, sx + (cell - Render2D.width(label)) / 2, sy + (cell - Render2D.FONT_HEIGHT) / 2 + 1, color);
			}
		}

		int size = Math.max(Math.round(cell * 0.8f), 6);
		if (!(target instanceof Player)) {
			double dx = target.getX() - ox, dz = target.getZ() - oz;
			int sx = Math.round(cx + (float) (-fz * dx + fx * dz) * cell);
			int sy = Math.round(cy - (float) (fx * dx + fz * dz) * cell);
			Render2D.rect(g, sx - size / 2, sy - size / 2, size, size, 0xFFE04848);
		}
		for (Player player : nearbyPlayers()) {
			double dx = player.getX() - ox, dz = player.getZ() - oz;
			int sx = Math.round(cx + (float) (-fz * dx + fx * dz) * cell);
			int sy = Math.round(cy - (float) (fx * dx + fz * dz) * cell);
			head(g, player, target, sx - size / 2, sy - size / 2, size);
		}
	}
}
