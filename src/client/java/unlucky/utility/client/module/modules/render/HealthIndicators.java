package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Floating {@code -4hp} / {@code +2hp} numbers that drift off a mob when its
 * health changes — red for damage, green for healing.
 *
 * <p>Nothing tells the client "this entity took 4 damage": the server syncs the
 * new health value and that's all. So we keep the last vitals we saw per entity
 * and diff them each tick. Consequences worth knowing: an entity we've only just
 * seen gets a baseline and no number (its health didn't change, we just met it),
 * and damage fully absorbed by armour shows nothing, because health is what we
 * can see and health didn't move.
 *
 * <p>What we diff is <b>health + absorption</b>, not health — a hit lands on the
 * absorption hearts first and leaves health untouched, so watching health alone
 * shows nothing for the whole time you're shielded. Caveat: {@code absorptionAmount}
 * is a plain field, <b>not synched entity data</b>. The client only really knows
 * its own (it simulates it from the effect packets — that's how the yellow hearts
 * render); for everyone else it reads 0, so the sum quietly collapses back to
 * health and other players' absorption hits stay invisible. Nothing to be done
 * client-side: the server never sends it.
 *
 * <p>Each number picks a random offset once and keeps it, so it drifts straight
 * up from where it started instead of wandering — the wander reads as a bug.
 */
public class HealthIndicators extends Module {
	/** Ignore changes under this: regen ticks are 0.5hp and would be constant noise. */
	private static final float MIN_CHANGE = 0.5f;
	/** Beyond this the numbers are unreadable clutter anyway. */
	private static final double MAX_DISTANCE_SQR = 48.0 * 48.0;
	private static final int MAX_LIVE = 64;

	public final BooleanSetting players = add(new BooleanSetting("Players", "Show on players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles", "Show on hostile mobs", true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives", "Show on passive mobs", true));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Show on yourself", true));
	public final BooleanSetting damage = add(new BooleanSetting("Damage", "Show damage taken", true));
	public final BooleanSetting healing = add(new BooleanSetting("Healing", "Show healing", true));
	public final ColorSetting damageColor = add(new ColorSetting("Damage color", "Color of damage numbers", 0xFFFF5555));
	public final ColorSetting healColor = add(new ColorSetting("Heal color", "Color of healing numbers", 0xFF55FF55));
	public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds a number stays up", 1.2, 0.3, 4.0, 0.1));
	public final NumberSetting rise = add(new NumberSetting("Rise", "Blocks the number drifts up over its life", 0.9, 0.0, 3.0, 0.1));
	public final NumberSetting spread = add(new NumberSetting("Spread", "How far around the mob they scatter", 0.4, 0.0, 1.5, 0.1));
	public final NumberSetting scale = add(new NumberSetting("Scale", "Text size", 1.0, 0.5, 2.0, 0.1));
	public final BooleanSetting shadow = add(new BooleanSetting("Shadow", "Drop shadow behind the text", true));

	/** Entity id -> last health / absorption we saw, so we can tell what changed. */
	private final Map<Integer, Float> lastHealth = new HashMap<>();
	private final Map<Integer, Float> lastAbsorption = new HashMap<>();
	private final List<Indicator> live = new ArrayList<>();
	private final Random rng = new Random();

	public HealthIndicators() {
		super("HealthIndicators", "Floating damage and healing numbers", Category.RENDER);
	}

	@Override
	protected void onDisable() {
		lastHealth.clear();
		lastAbsorption.clear();
		live.clear();
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}
			if (!shows(living) || mc().player.distanceToSqr(living) > MAX_DISTANCE_SQR) {
				lastHealth.remove(living.getId());
				lastAbsorption.remove(living.getId());
				continue;
			}
			float health = living.getHealth();
			float absorption = living.getAbsorptionAmount();
			Float wasHealth = lastHealth.put(living.getId(), health);
			Float wasAbsorption = lastAbsorption.put(living.getId(), absorption);
			// first sight: take a baseline. Its health didn't change, we just met it
			if (wasHealth == null || wasAbsorption == null) {
				continue;
			}
			float change = (health + absorption) - (wasHealth + wasAbsorption);
			if (Math.abs(change) < MIN_CHANGE) {
				continue;
			}
			if (change < 0.0f) {
				if (!damage.get() || !wasHit(living, health, wasHealth)) {
					continue;
				}
			} else if (!healing.get()) {
				continue;
			}
			spawn(living, change);
		}
		// drop entities that left, so their vitals can't be diffed against a stale
		// value if they come back and the maps can't grow forever
		lastHealth.keySet().removeIf(id -> mc().level.getEntity(id) == null);
		lastAbsorption.keySet().removeIf(id -> mc().level.getEntity(id) == null);

		for (Iterator<Indicator> it = live.iterator(); it.hasNext();) {
			if (++it.next().age > duration.get() * 20.0) {
				it.remove();
			}
		}
	}

	/**
	 * Was that drop actually a hit? Health only ever falls from damage, so a health
	 * drop is proof by itself. Absorption also falls when the effect simply runs
	 * out — a gapple timing out would otherwise flash a red {@code -4hp} at you for
	 * nothing — so for an absorption-only drop we lean on {@code hurtTime}, which
	 * vanilla sets to 10 on every hit that lands and ticks down from there.
	 */
	private boolean wasHit(LivingEntity living, float health, float wasHealth) {
		return health < wasHealth || living.hurtTime > 0;
	}

	private boolean shows(LivingEntity living) {
		if (living == mc().player) {
			return self.get();
		}
		if (!living.isAlive()) {
			return false;
		}
		return CombatUtil.validTarget(living, players.get(), hostiles.get(), passives.get());
	}

	private void spawn(LivingEntity living, float change) {
		if (live.size() >= MAX_LIVE) {
			live.remove(0); // oldest goes; a wall of numbers helps nobody
		}
		double s = spread.get();
		live.add(new Indicator(
				living.getId(),
				(rng.nextDouble() - 0.5) * 2.0 * s,
				living.getBbHeight() * (0.5 + rng.nextDouble() * 0.4),
				(rng.nextDouble() - 0.5) * 2.0 * s,
				change));
	}

	/** Called from the HUD layer every frame — including while off, so gate here. */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!isEnabled() || mc().level == null || mc().player == null || live.isEmpty()) {
			return;
		}
		int guiWidth = g.guiWidth();
		int guiHeight = g.guiHeight();
		float life = (float) (duration.get() * 20.0);
		for (Indicator indicator : live) {
			Entity entity = mc().level.getEntity(indicator.entityId);
			if (entity == null) {
				continue; // died or unloaded mid-flight; it just stops being drawn
			}
			float progress = Math.min((indicator.age + partialTick) / life, 1.0f);
			Vec3 base = entity.getPosition(partialTick);
			Vec3 at = base.add(indicator.offsetX,
					indicator.offsetY + progress * rise.get(),
					indicator.offsetZ);
			Vec3 screen = Render3D.worldToScreen(at, guiWidth, guiHeight);
			if (screen == null) {
				continue; // behind the camera
			}
			// hold full opacity for the first half, then fade out
			float alpha = progress < 0.5f ? 1.0f : 1.0f - (progress - 0.5f) * 2.0f;
			int argb = ((int) (alpha * 255.0f) << 24)
					| ((indicator.change < 0.0f ? damageColor.get() : healColor.get()) & 0xFFFFFF);
			draw(g, indicator.label(), (int) screen.x, (int) screen.y, argb);
		}
	}

	private void draw(GuiGraphicsExtractor g, String text, int x, int y, int argb) {
		float s = scale.getFloat();
		int half = Render2D.width(text) / 2;
		if (s == 1.0f) {
			drawText(g, text, x - half, y, argb);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		drawText(g, text, -half, 0, argb);
		g.pose().popMatrix();
	}

	private void drawText(GuiGraphicsExtractor g, String text, int x, int y, int argb) {
		if (shadow.get()) {
			Render2D.text(g, text, x, y, argb);
		} else {
			Render2D.textNoShadow(g, text, x, y, argb);
		}
	}

	/**
	 * One floating number. The offset is rolled once at spawn and kept, so it
	 * rises in a straight line from where it appeared.
	 */
	private static final class Indicator {
		final int entityId;
		final double offsetX;
		final double offsetY;
		final double offsetZ;
		final float change;
		int age;

		Indicator(int entityId, double offsetX, double offsetY, double offsetZ, float change) {
			this.entityId = entityId;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.offsetZ = offsetZ;
			this.change = change;
		}

		/** {@code -4hp} / {@code +2.5hp} — halves shown, whole numbers kept clean. */
		String label() {
			float shown = Math.abs(change);
			String amount = shown == Math.floor(shown)
					? Integer.toString((int) shown)
					: String.format("%.1f", shown);
			return (change < 0.0f ? "-" : "+") + amount + "hp";
		}
	}
}
