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
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.combat.Aura;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
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
	public final BooleanSetting enabled = add(new BooleanSetting("TargetHUD", "Card with your combat target's model and health", false));
	public final BooleanSetting backing = add(new BooleanSetting("TargetHUD bg", "Backing behind the target card", true));
	public final ModeSetting layout = add(new ModeSetting("Target layout", "Compact status, classic card, or detailed equipment card", "Detailed", "Compact", "Classic", "Detailed"));
	public final ModeSetting source = add(new ModeSetting("Target source", "Which target the card shows", "Both", "Aura", "Crosshair", "Both"));
	public final BooleanSetting showModel = add(new BooleanSetting("Target model", "Live model on the card", true));
	public final NumberSetting modelScale = add(new NumberSetting("Target model scale", "Scale of the target model/head area", 100, 60, 160, 5));
	public final BooleanSetting showHealthText = add(new BooleanSetting("Health number", "Numeric health next to the bar", true));
	public final ModeSetting healthFormat = add(new ModeSetting("Health format", "Number, percentage, or both", "Number", "Number", "Percent", "Both"));
	public final BooleanSetting showDistance = add(new BooleanSetting("Target distance", "Append distance to the target name", false));
	public final BooleanSetting showPing = add(new BooleanSetting("Target ping", "Show player latency when available", false));
	public final BooleanSetting showAbsorption = add(new BooleanSetting("Target absorption", "Include golden absorption health", true));
	public final ModeSetting healthColor = add(new ModeSetting("Health bar color", "Health gradient or shared HUD accent", "Health", "Health", "Accent"));
	public final BooleanSetting hurtFlash = add(new BooleanSetting("Hurt flash", "Red wash over the card when the target is hit", true));
	public final ModeSetting hurtStyle = add(new ModeSetting("Hurt animation", "Flash, shake, or pulse when the target takes damage", "Flash", "Flash", "Shake", "Pulse", "None"));
	public final BooleanSetting showGear = add(new BooleanSetting("Gear", "Armor and held items (glint and durability shown)", true));
	public final ModeSetting gearDurability = add(new ModeSetting("Gear durability", "Durability bars, percentages, both, or neither", "Bar", "Bar", "Percent", "Both", "None"));
	public final BooleanSetting enchants = add(new BooleanSetting("Enchants", "Abbreviated enchant tags for the target's gear", true));
	public final BooleanSetting potions = add(new BooleanSetting("Potions", "The target's active potion effects", true));

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
		modelScale.showWhen(showModel::get);
		healthFormat.showWhen(showHealthText::get);
		hurtStyle.showWhen(hurtFlash::get);
		gearDurability.showWhen(showGear::get);
		enchants.showWhen(showGear::get);
		potions.showWhen(() -> !layout.is("Compact"));
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	public boolean requiresPlayer() {
		return false;
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
		return switch (source.get()) {
			case "Crosshair" -> crosshair;
			case "Both" -> aura != null ? aura : crosshair;
			default -> aura;
		};
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		boolean preview = editing && HudManager.isPreviewData();
		if (mc().player == null || mc().level == null) {
			if (preview) {
				drawEditorPreview(g);
			} else if (editing) {
				drawEmptyEditorCard(g);
			} else {
				setSize(0, 0);
			}
			return;
		}
		LivingEntity target = pickTarget();
		if (preview && target == null) {
			target = mc().player; // placeholder so the card can be positioned
		} else if (editing && target == null) {
			drawEmptyEditorCard(g);
			return;
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

		boolean compact = layout.is("Compact");
		boolean detailed = layout.is("Detailed");
		int textLine = Math.max(9, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int enchLine = Math.max(ENCH_LINE, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale() * ENCH_SCALE) + 1);
		int headerH = Math.max(compact ? 30 : detailed ? 42 : HEADER_H, textLine + 18);
		int modelW = Math.max(20, Math.round((compact ? 22 : MODEL_W) * modelScale.getFloat() / 100.0f));
		boolean model = showModel.get();
		int left = model ? modelW + 6 : PAD;
		int a = (int) (alpha * 255);

		String name = shown.getDisplayName().getString();
		if (showDistance.get()) {
			name += " " + String.format(java.util.Locale.ROOT, "%.1fm", mc().player.distanceTo(shown));
		}
		if (showPing.get()) {
			int ping = ping(shown);
			if (ping >= 0) name += " " + ping + "ms";
		}
		float health = Math.max(target != null ? target.getHealth() : 0.0f, 0.0f);
		displayedHealth += (health - displayedHealth) * 0.2f;
		float maxHealth = Math.max(shown.getMaxHealth(), 1.0f);
		float frac = Math.clamp(displayedHealth / maxHealth, 0.0f, 1.0f);
		String number = String.format(java.util.Locale.ROOT, "%.1f", displayedHealth);
		String percent = Math.round(frac * 100.0f) + "%";
		String healthText = switch (healthFormat.get()) {
			case "Percent" -> percent;
			case "Both" -> number + " (" + percent + ")";
			default -> number;
		};
		if (showAbsorption.get() && shown.getAbsorptionAmount() > 0) {
			healthText += " +" + (int) shown.getAbsorptionAmount();
		}

		boolean showEnch = enchants.get();
		List<ItemStack> gear = showGear.get() && !compact ? GearUtil.gear(shown) : List.of();
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
		if (potions.get() && !compact) {
			effects.addAll(shown.getActiveEffects());
			effects.sort(Comparator.comparingInt(e -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
		}

		// width: widest of header / gear-column block / potion rows
		int width = Math.max(compact ? 106 : detailed ? 136 : 120,
				left + Render2D.width(name) + 8 + (showHealthText.get() ? Render2D.width(healthText) : 0) + PAD);
		if (!gear.isEmpty()) {
			width = Math.max(width, PAD + gearBlockW + PAD);
		}
		width = Math.max(width, PAD + effects.size() * POTION_W + PAD);
		width = Math.min(width, 240);

		int y = getY() + headerH;
		int gearY = y;
		boolean durabilityBar = gearDurability.is("Bar") || gearDurability.is("Both");
		boolean durabilityPercent = gearDurability.is("Percent") || gearDurability.is("Both");
		int durabilityH = (durabilityBar ? 3 : 0) + (durabilityPercent ? textLine : 0);
		int gearBlockH = gear.isEmpty() ? 0 : 18 + durabilityH + maxEnchLines * enchLine + 2;
		y += gearBlockH;
		int potionY = y;
		if (!effects.isEmpty()) {
			y += Math.max(24, 17 + textLine);
		}
		int height = y - getY();
		setSize(width, height);

		boolean hurt = hurtFlash.get() && !hurtStyle.is("None") && shown.hurtTime > 0;
		int shakeX = hurt && hurtStyle.is("Shake") ? ((shown.hurtTime & 1) == 0 ? 2 : -2) : 0;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(shakeX, 0);
		int bg = Theme.hudBg(backing.get());
		Render2D.hudPanel(g, getX(), getY(), width, height,
				ColorUtil.withAlpha(bg, (int) (alpha * (bg >>> 24))));

		if (model && alpha > 0.5f) {
			HudEntity.draw(g, shown, getX() + 2, getY() + 2, getX() + 2 + modelW, getY() + headerH - 2,
					20.0f * modelScale.getFloat() / 100.0f, 0.0f, 0.0f);
		}
		Render2D.text(g, name, getX() + left, getY() + 6, ColorUtil.withAlpha(Theme.text, a));
		if (showHealthText.get()) {
			int color = shown.getAbsorptionAmount() > 0 ? 0xFFF2C94C : Theme.textDim;
			Render2D.text(g, healthText, getX() + width - PAD - Render2D.width(healthText), getY() + 6,
					ColorUtil.withAlpha(color, a));
		}

		// health bar: track + fill colored red->green by fraction
		int barX = getX() + left;
		int barW = width - left - PAD;
		int barY = getY() + headerH - 12;
		Render2D.rect(g, barX, barY, barW, 4, ColorUtil.withAlpha(0xFF000000, a / 2));
		int fillColor = healthColor.is("Accent") ? accentAt(barY, g.guiHeight())
				: ColorUtil.lerp(0xFFE04545, 0xFF3FD46A, frac);
		Render2D.rect(g, barX, barY, (int) (barW * frac), 4, ColorUtil.withAlpha(fillColor, a));

		// gear columns: icon (glint + durability free from item render) with its
		// own enchants listed beneath in small text
		if (!gear.isEmpty() && alpha > 0.5f) {
			int cx = getX() + PAD;
			for (int i = 0; i < gear.size(); i++) {
				int cw = colW[i];
				ItemStack stack = gear.get(i);
				g.item(stack, cx + (cw - 16) / 2, gearY + 1);
				float remaining = stack.isDamageableItem() && stack.getMaxDamage() > 0
						? Math.clamp(1.0f - (float) stack.getDamageValue() / stack.getMaxDamage(), 0.0f, 1.0f) : 1.0f;
				int detailY = gearY + 18;
				if (durabilityBar && stack.isDamageableItem()) {
					Render2D.rect(g, cx + 1, detailY, cw - 2, 2, 0x80000000);
					Render2D.rect(g, cx + 1, detailY, Math.round((cw - 2) * remaining), 2, durabilityColor(remaining));
					detailY += 3;
				}
				if (durabilityPercent && stack.isDamageableItem()) {
					String value = Math.round(remaining * 100.0f) + "%";
					Render2D.text(g, value, cx + (cw - Render2D.width(value)) / 2, detailY, durabilityColor(remaining));
					detailY += textLine;
				}
				List<String> chips = itemEnch.get(i);
				for (int j = 0; j < chips.size(); j++) {
					String c = chips.get(j);
					float tw = Render2D.width(c) * ENCH_SCALE;
					scaledText(g, c, cx + (cw - tw) / 2f, detailY + j * enchLine, ENCH_SCALE,
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
		if (hurt && hurtStyle.is("Flash")) {
			int flash = (int) (alpha * 70.0f * (shown.hurtTime / 10.0f));
			Render2D.roundedRect(g, getX(), getY(), width, height, Theme.hudPanelRadius,
					ColorUtil.withAlpha(0xFFE04545, flash));
		} else if (hurt && hurtStyle.is("Pulse")) {
			int pulse = (int) (90.0f * (0.5f + 0.5f * Math.sin(System.currentTimeMillis() / 80.0)));
			int color = ColorUtil.withAlpha(0xFFE04545, pulse);
			Render2D.rect(g, getX(), getY(), width, 1, color);
			Render2D.rect(g, getX(), getY() + height - 1, width, 1, color);
			Render2D.rect(g, getX(), getY(), 1, height, color);
			Render2D.rect(g, getX() + width - 1, getY(), 1, height, color);
		}
		pose.popMatrix();
	}

	private void drawEditorPreview(GuiGraphicsExtractor g) {
		boolean compact = layout.is("Compact");
		boolean detailed = layout.is("Detailed");
		int textLine = Math.max(9, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int width = compact ? 112 : detailed ? 142 : 124;
		int barLocalY = Math.max(compact ? 23 : 28, showHealthText.get() ? 6 + textLine * 2 : 7 + textLine);
		int gearLocalY = barLocalY + 12;
		int height = detailed && showGear.get() ? gearLocalY + 21 : barLocalY + 7;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, backing.get());
		int left = showModel.get() ? (compact ? 28 : 36) : PAD;
		if (showModel.get()) {
			Render2D.roundedGradient(g, getX() + 5, getY() + 5, compact ? 18 : 25, compact ? 18 : 28, 5,
					accentAt(0, 28), accentAt(28, 28));
			Render2D.textNoShadow(g, "D", getX() + (compact ? 11 : 14), getY() + (compact ? 10 : 13), 0xFFFFFFFF);
		}
		String title = "Training Dummy";
		if (showDistance.get()) title += " 4.2m";
		if (showPing.get()) title += " 48ms";
		Render2D.text(g, title, getX() + left, getY() + 5, Theme.text);
		String value = healthFormat.is("Percent") ? "72%" : healthFormat.is("Both") ? "14.4 (72%)" : "14.4";
		if (showAbsorption.get()) value += " +4";
		if (showHealthText.get()) Render2D.text(g, value, getX() + width - PAD - Render2D.width(value), getY() + 5 + textLine, 0xFFF2C94C);
		int barY = getY() + barLocalY;
		Render2D.rect(g, getX() + left, barY, width - left - PAD, 4, 0x70000000);
		int color = healthColor.is("Accent") ? accentAt(barY, g.guiHeight()) : ColorUtil.lerp(0xFFE04545, 0xFF3FD46A, 0.72f);
		Render2D.rect(g, getX() + left, barY, Math.round((width - left - PAD) * 0.72f), 4, color);
		if (detailed && showGear.get()) {
			for (int i = 0; i < 5; i++) {
				int x = getX() + PAD + i * 21;
				Render2D.roundedRect(g, x, getY() + gearLocalY, 16, 16, 3, ColorUtil.withAlpha(accentAt(i, 5), 150));
				if (!gearDurability.is("None")) Render2D.rect(g, x, getY() + gearLocalY + 17, 13 - i, 2, durabilityColor(1.0f - i * 0.16f));
			}
		}
	}

	private void drawEmptyEditorCard(GuiGraphicsExtractor g) {
		int width = 112;
		int height = Math.max(layout.is("Compact") ? 26 : 34,
				(int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 18);
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, backing.get());
		Render2D.text(g, "TargetHUD", getX() + 6, getY() + 5, Theme.textDim);
		Render2D.rect(g, getX() + 6, getY() + height - 9, width - 12, 3, 0x50000000);
	}

	private int ping(LivingEntity entity) {
		if (mc().getConnection() == null) return -1;
		var info = mc().getConnection().getPlayerInfo(entity.getUUID());
		return info == null ? -1 : Math.max(0, info.getLatency());
	}

	private static int durabilityColor(float remaining) {
		return remaining < 0.5f
				? ColorUtil.lerp(0xFFE04545, 0xFFE0C020, remaining * 2.0f)
				: ColorUtil.lerp(0xFFE0C020, 0xFF3FD46A, (remaining - 0.5f) * 2.0f);
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
