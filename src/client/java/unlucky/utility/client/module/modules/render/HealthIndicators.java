package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.HealthChangeTracker;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Floating {@code -4hp} / {@code +2hp} numbers that drift off a mob when its
 * health changes — red for damage, green for healing.
 *
 * <p>The diff that recovers "took 4 damage" from a synced health value lives in
 * {@link HealthChangeTracker}, along with the two caveats that come with it —
 * absorption is not synced for anybody but you, and an absorption drop needs
 * {@code hurtTime} to confirm it was a hit rather than the effect expiring. This
 * module owns only the display: which entities to show, and the numbers that drift
 * off them. HitEffects reads the same events, so the two can never disagree about
 * what happened or both claim the same change.
 *
 * <p>Each number picks a random offset once and keeps it, so it drifts straight
 * up from where it started instead of wandering — the wander reads as a bug.
 */
public class HealthIndicators extends Module {
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

	private final List<Indicator> live = new ArrayList<>();
	private final Random rng = new Random();

	public HealthIndicators() {
		super("HealthIndicators", "Floating damage and healing numbers", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		HealthChangeTracker.addConsumer(this);
	}

	@Override
	protected void onDisable() {
		HealthChangeTracker.removeConsumer(this);
		live.clear();
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		for (HealthChangeTracker.Event event : HealthChangeTracker.events()) {
			LivingEntity living = event.entity();
			if (!shows(living) || mc().player.distanceToSqr(living) > MAX_DISTANCE_SQR) {
				continue;
			}
			if (event.damage() ? !damage.get() : !healing.get()) {
				continue;
			}
			spawn(living, event.change());
		}

		for (Iterator<Indicator> it = live.iterator(); it.hasNext();) {
			if (++it.next().age > duration.get() * 20.0) {
				it.remove();
			}
		}
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
