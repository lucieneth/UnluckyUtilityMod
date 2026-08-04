package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Your own active potion effects, sorted by remaining time. Full mode lists each
 * with name, level and mm:ss; compact mode is an icon strip. Rows pulse when a
 * effect is about to expire.
 */
public class PotionHudWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("PotionHUD", "Your active potion effects", false));
	public final BooleanSetting bg = add(new BooleanSetting("Potions bg", "Backing behind the effect list", true));
	public final BooleanSetting compact = add(new BooleanSetting("Potions compact", "Icon strip instead of a labelled list", false));
	public final BooleanSetting hideAmbient = add(new BooleanSetting("Hide ambient", "Hide beacon/ambient effects", false));
	public final ModeSetting sort = add(new ModeSetting("Potion sort", "Sort effects by remaining time, name or effect type", "Duration", "Duration", "Name", "Type"));
	public final BooleanSetting showTimer = add(new BooleanSetting("Potion timer", "Show remaining time beside each effect", true));
	public final ModeSetting display = add(new ModeSetting("Potion display", "Full rows, text-only rows, or compact icons", "Full", "Full", "Text", "Icons"));
	public final ModeSetting grouping = add(new ModeSetting("Potion grouping", "Keep all effects together or separate beneficial and harmful", "None", "None", "Type"));
	public final ColorSetting beneficialColor = add(new ColorSetting("Beneficial color", "Text and timer color for beneficial effects", 0xFF3FD46A));
	public final ColorSetting harmfulColor = add(new ColorSetting("Harmful color", "Text and timer color for harmful effects", 0xFFE04545));
	public final BooleanSetting timerBar = add(new BooleanSetting("Potion timer bar", "Show a remaining-duration bar for each effect", true));
	private static final List<MobEffectInstance> PREVIEW_EFFECTS = List.of(
			new MobEffectInstance(MobEffects.SPEED, 20 * 75, 1),
			new MobEffectInstance(MobEffects.STRENGTH, 20 * 42),
			new MobEffectInstance(MobEffects.POISON, 20 * 12));
	private final java.util.IdentityHashMap<MobEffectInstance, Integer> initialDurations = new java.util.IdentityHashMap<>();

	private static final int ICON = 16;
	private static final int ROW = 18;
	private static final int MAX_TEXT = 120;
	private static final String[] ROMAN = {"", "", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	public PotionHudWidget() {
		super("PotionHUD");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.15);
	}

	private List<MobEffectInstance> effects(boolean editing) {
		List<MobEffectInstance> list = new ArrayList<>();
		if (mc().player != null) {
			for (MobEffectInstance e : mc().player.getActiveEffects()) {
				if (hideAmbient.get() && e.isAmbient()) {
					continue;
				}
				list.add(e);
			}
		}
		if (list.isEmpty() && editing && HudManager.isPreviewData()) {
			list.addAll(PREVIEW_EFFECTS);
		}
		switch (sort.get()) {
			case "Name" -> list.sort(Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString(), String.CASE_INSENSITIVE_ORDER));
			case "Type" -> list.sort(Comparator.comparing(e -> e.getEffect().value().isBeneficial()));
			default -> list.sort(Comparator.comparingInt(e -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
		}
		return list;
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		List<MobEffectInstance> effects = effects(editing);
		initialDurations.keySet().retainAll(effects);
		if (effects.isEmpty()) {
			setSize(0, 0);
			return;
		}
		if (horizontalLayout() || (!verticalLayout() && (compact.get() || display.is("Icons")))) {
			drawCompact(g, effects);
		} else if (display.is("Text")) {
			drawText(g, effects, Math.max(12, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 3));
		} else {
			int textLine = (int) Math.ceil(Render2D.FONT_HEIGHT * textScale());
			drawFull(g, effects, Math.max(ROW, textLine * 2 + 3));
		}
	}

	private void drawCompact(GuiGraphicsExtractor g, List<MobEffectInstance> effects) {
		int width = effects.size() * (ICON + 2);
		setSize(width, ICON);
		Render2D.hudPanel(g, getX(), getY(), width, ICON, bg.get());
		int x = getX();
		for (MobEffectInstance e : effects) {
			int alpha = pulse(e, 255);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(e.getEffect()),
					x, getY(), ICON, ICON, ARGB.white(alpha));
			String r = roman(e.getAmplifier());
			if (!r.isEmpty()) {
				Render2D.text(g, r, x + ICON - Render2D.width(r), getY() + ICON - 8,
						ColorUtil.withAlpha(Theme.text, alpha));
			}
			x += ICON + 2;
		}
	}

	private void drawFull(GuiGraphicsExtractor g, List<MobEffectInstance> effects, int rowHeight) {
		int textW = 0;
		for (MobEffectInstance e : effects) {
			textW = Math.max(textW, Render2D.width(title(e)));
			if (showTimer.get()) {
				textW = Math.max(textW, Render2D.width(time(e)));
			}
		}
		textW = Math.min(textW, MAX_TEXT);
		int width = ICON + 4 + textW + 6;
		int groupGap = grouping.is("Type") && hasBothTypes(effects) ? 4 : 0;
		int height = effects.size() * rowHeight + groupGap + 2;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());

		int y = getY() + 2;
		Boolean previousType = null;
		for (MobEffectInstance e : effects) {
			boolean beneficial = e.getEffect().value().isBeneficial();
			if (grouping.is("Type") && previousType != null && previousType != beneficial) y += 4;
			previousType = beneficial;
			int alpha = pulse(e, 255);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(e.getEffect()),
					getX() + 2, y, ICON, ICON, ARGB.white(alpha));
			int tx = getX() + ICON + 6;
			int effectColor = beneficial ? beneficialColor.get() : harmfulColor.get();
			Render2D.text(g, title(e), tx, y + 1, ColorUtil.withAlpha(effectColor, alpha));
			if (showTimer.get()) {
				Render2D.text(g, time(e), tx, y + Math.max(10, (rowHeight - 3) / 2), ColorUtil.withAlpha(effectColor, alpha));
			}
			if (timerBar.get() && !e.isInfiniteDuration()) {
				int barW = Math.max(width - tx + getX() - 4, 4);
				Render2D.rect(g, tx, y + rowHeight - 2, barW, 1, 0x50000000);
				Render2D.rect(g, tx, y + rowHeight - 2, Math.round(barW * durationFraction(e)), 1, ColorUtil.withAlpha(effectColor, alpha));
			}
			y += rowHeight;
		}
	}

	private void drawText(GuiGraphicsExtractor g, List<MobEffectInstance> effects, int rowHeight) {
		int width = 10;
		for (MobEffectInstance e : effects) {
			String row = title(e) + (showTimer.get() ? "  " + time(e) : "");
			width = Math.max(width, Render2D.width(row) + 10);
		}
		int height = effects.size() * rowHeight + 4;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		int y = getY() + 3;
		for (MobEffectInstance e : effects) {
			int color = e.getEffect().value().isBeneficial() ? beneficialColor.get() : harmfulColor.get();
			String row = title(e) + (showTimer.get() ? "  " + time(e) : "");
			Render2D.text(g, row, alignedX(Render2D.width(row), 5), y, color);
			if (timerBar.get() && !e.isInfiniteDuration()) {
				int barW = width - 10;
				Render2D.rect(g, getX() + 5, y + rowHeight - 2, Math.round(barW * durationFraction(e)), 1, color);
			}
			y += rowHeight;
		}
	}

	private float durationFraction(MobEffectInstance effect) {
		int initial = initialDurations.merge(effect, effect.getDuration(), Math::max);
		return initial <= 0 ? 1.0f : Math.clamp(effect.getDuration() / (float) initial, 0.0f, 1.0f);
	}

	private static boolean hasBothTypes(List<MobEffectInstance> effects) {
		boolean beneficial = false, harmful = false;
		for (MobEffectInstance e : effects) {
			if (e.getEffect().value().isBeneficial()) beneficial = true; else harmful = true;
		}
		return beneficial && harmful;
	}

	/** Pulses the alpha when the effect is nearly gone. */
	private static int pulse(MobEffectInstance e, int base) {
		if (e.isInfiniteDuration() || e.getDuration() >= 100) {
			return base;
		}
		return (int) (base * (0.4f + 0.6f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 160.0))));
	}

	private static String title(MobEffectInstance e) {
		String name = e.getEffect().value().getDisplayName().getString();
		String r = roman(e.getAmplifier());
		return r.isEmpty() ? name : name + " " + r;
	}

	private static String roman(int amplifier) {
		int n = amplifier + 1;
		return n < ROMAN.length ? ROMAN[n] : Integer.toString(n);
	}

	private static String time(MobEffectInstance e) {
		if (e.isInfiniteDuration()) {
			return "∞";
		}
		int s = e.getDuration() / 20;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}
}
