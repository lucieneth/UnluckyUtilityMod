package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.combat.Aura;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.GearUtil;
import unlucky.utility.client.util.HudEntity;
import unlucky.utility.client.util.Render2D;

/**
 * Card showing the current combat target: live model, name, an interpolating
 * health bar with hurt flash, plus optional gear (icons carry vanilla glint
 * and durability bars), abbreviated enchant chips and the target's potions.
 * Slides in and out with a fade when a target is gained or lost.
 */
public class TargetHudWidget extends HudWidget {
	private static final int HEADER_H = 38;
	private static final int MODEL_W = 30;
	private static final int PAD = 6;
	private static final int GEAR = 18;
	private static final int POTION_W = 24;
	private static final float ENCH_SCALE = 0.7f; // small text under each gear icon
	private static final int ENCH_LINE = 7;
	private static final int COL_GAP = 3;

	private final Animation fade = new Animation(200, false, Easing.QUAD_OUT);
	private LivingEntity shown; // kept during fade-out so the card empties gracefully
	private float displayedHealth;

	public TargetHudWidget() {
		super("TargetHUD");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().targetHud.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.6);
	}

	private LivingEntity pickTarget() {
		if (mc().level == null) {
			return null;
		}
		LivingEntity aura = Aura.currentTarget instanceof LivingEntity living && living.isAlive() ? living : null;
		LivingEntity crosshair = mc().crosshairPickEntity instanceof LivingEntity living
				&& living.isAlive() && living != mc().player ? living : null;
		return switch (hud().targetHudSource.get()) {
			case "Crosshair" -> crosshair;
			case "Both" -> aura != null ? aura : crosshair;
			default -> aura;
		};
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		LivingEntity target = pickTarget();
		if (editing && target == null) {
			target = mc().player; // placeholder so the card can be positioned
		}
		if (target != null && target != shown) {
			shown = target;
			displayedHealth = target.getHealth(); // snap on a fresh target
		}
		fade.setDirection(target != null);
		float alpha = fade.value();
		if (shown == null || (alpha < 0.02f && target == null)) {
			shown = null;
			setSize(0, 0);
			return;
		}

		HudModule hud = hud();
		boolean model = hud.targetHudModel.get();
		int left = model ? MODEL_W + 6 : PAD;
		int a = (int) (alpha * 255);

		String name = shown.getDisplayName().getString();
		float health = Math.max(target != null ? target.getHealth() : 0.0f, 0.0f);
		displayedHealth += (health - displayedHealth) * 0.2f;
		float maxHealth = Math.max(shown.getMaxHealth(), 1.0f);
		float frac = Math.clamp(displayedHealth / maxHealth, 0.0f, 1.0f);
		String healthText = String.format("%.1f", displayedHealth)
				+ (shown.getAbsorptionAmount() > 0 ? " +" + (int) shown.getAbsorptionAmount() : "");

		boolean showEnch = hud.targetHudEnchants.get();
		List<ItemStack> gear = hud.targetHudGear.get() ? GearUtil.gear(shown) : List.of();
		// each gear item becomes a column: icon on top, its enchants listed below
		List<List<String>> itemEnch = new ArrayList<>();
		int[] colW = new int[gear.size()];
		int gearBlockW = 0;
		int maxEnchLines = 0;
		for (int i = 0; i < gear.size(); i++) {
			List<String> chips = showEnch ? GearUtil.enchantChips(gear.get(i)) : List.of();
			itemEnch.add(chips);
			int w = GEAR;
			for (String c : chips) {
				w = Math.max(w, (int) Math.ceil(Render2D.width(c) * ENCH_SCALE) + 2);
			}
			colW[i] = w;
			gearBlockW += w + (i > 0 ? COL_GAP : 0);
			maxEnchLines = Math.max(maxEnchLines, chips.size());
		}

		List<MobEffectInstance> effects = new ArrayList<>();
		if (hud.targetHudPotions.get()) {
			effects.addAll(shown.getActiveEffects());
			effects.sort(Comparator.comparingInt(e -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
		}

		// width: widest of header / gear-column block / potion rows
		int width = Math.max(120, left + Render2D.width(name) + 8 + Render2D.width(healthText) + PAD);
		if (!gear.isEmpty()) {
			width = Math.max(width, PAD + gearBlockW + PAD);
		}
		width = Math.max(width, PAD + effects.size() * POTION_W + PAD);
		width = Math.min(width, 240);

		int y = getY() + HEADER_H;
		int gearY = y;
		int gearBlockH = gear.isEmpty() ? 0 : 18 + maxEnchLines * ENCH_LINE + 2;
		y += gearBlockH;
		int potionY = y;
		if (!effects.isEmpty()) {
			y += 24;
		}
		int height = y - getY();
		setSize(width, height);

		int bg = Theme.hudBg(hud.targetHudBg.get());
		Render2D.roundedRect(g, getX(), getY(), width, height, 4,
				ColorUtil.withAlpha(bg, (int) (alpha * (bg >>> 24))));

		if (model && alpha > 0.5f) {
			HudEntity.draw(g, shown, getX() + 2, getY() + 2, getX() + 2 + MODEL_W, getY() + HEADER_H - 2, 20.0f, 0.0f, 0.0f);
		}
		Render2D.text(g, name, getX() + left, getY() + 6, ColorUtil.withAlpha(Theme.text, a));
		if (hud.targetHudHealthText.get()) {
			int color = shown.getAbsorptionAmount() > 0 ? 0xFFF2C94C : Theme.textDim;
			Render2D.text(g, healthText, getX() + width - PAD - Render2D.width(healthText), getY() + 6,
					ColorUtil.withAlpha(color, a));
		}

		// health bar: track + fill colored red->green by fraction
		int barX = getX() + left;
		int barW = width - left - PAD;
		int barY = getY() + HEADER_H - 12;
		Render2D.rect(g, barX, barY, barW, 4, ColorUtil.withAlpha(0xFF000000, a / 2));
		Render2D.rect(g, barX, barY, (int) (barW * frac), 4,
				ColorUtil.withAlpha(ColorUtil.lerp(0xFFE04545, 0xFF3FD46A, frac), a));

		// gear columns: icon (glint + durability free from item render) with its
		// own enchants listed beneath in small text
		if (!gear.isEmpty() && alpha > 0.5f) {
			int cx = getX() + PAD;
			for (int i = 0; i < gear.size(); i++) {
				int cw = colW[i];
				g.item(gear.get(i), cx + (cw - 16) / 2, gearY + 1);
				List<String> chips = itemEnch.get(i);
				for (int j = 0; j < chips.size(); j++) {
					String c = chips.get(j);
					float tw = Render2D.width(c) * ENCH_SCALE;
					scaledText(g, c, cx + (cw - tw) / 2f, gearY + 18 + j * ENCH_LINE, ENCH_SCALE,
							ColorUtil.withAlpha(Theme.accent2, a));
				}
				cx += cw + COL_GAP;
			}
		}

		// potions: icon, amplifier badge, mm:ss timer that pulses when low
		if (!effects.isEmpty()) {
			int px = getX() + PAD;
			for (MobEffectInstance effect : effects) {
				g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect.getEffect()),
						px, potionY, 16, 16, ARGB.white(a));
				if (effect.getAmplifier() > 0) {
					String lvl = Integer.toString(effect.getAmplifier() + 1);
					Render2D.text(g, lvl, px + 16 - Render2D.width(lvl), potionY, ColorUtil.withAlpha(Theme.text, a));
				}
				String time = timer(effect);
				int tint = a;
				if (!effect.isInfiniteDuration() && effect.getDuration() < 100) {
					tint = (int) (a * (0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 120.0)));
				}
				Render2D.text(g, time, px + 8 - Render2D.width(time) / 2, potionY + 17, ColorUtil.withAlpha(Theme.textDim, tint));
				px += POTION_W;
			}
		}

		// hurt flash: red wash over the card, strongest right after the hit
		if (hud.targetHudHurtFlash.get() && shown.hurtTime > 0) {
			int flash = (int) (alpha * 70.0f * (shown.hurtTime / 10.0f));
			Render2D.roundedRect(g, getX(), getY(), width, height, 4, ColorUtil.withAlpha(0xFFE04545, flash));
		}
	}

	private static String timer(MobEffectInstance effect) {
		if (effect.isInfiniteDuration()) {
			return "∞";
		}
		int s = effect.getDuration() / 20;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	/** Draws text scaled down about its top-left corner. */
	private static void scaledText(GuiGraphicsExtractor g, String text, float x, float y, float scale, int color) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		Render2D.textNoShadow(g, text, 0, 0, color);
		pose.popMatrix();
	}
}
