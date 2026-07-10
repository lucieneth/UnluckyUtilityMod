package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffectInstance;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Your own active potion effects, sorted by remaining time. Full mode lists each
 * with name, level and mm:ss; compact mode is an icon strip. Rows pulse when a
 * effect is about to expire.
 */
public class PotionHudWidget extends HudWidget {
	private static final int ICON = 16;
	private static final int ROW = 18;
	private static final int MAX_TEXT = 120;
	private static final String[] ROMAN = {"", "", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	public PotionHudWidget() {
		super("PotionHUD");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().potionHud.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.15);
	}

	private List<MobEffectInstance> effects(boolean editing) {
		List<MobEffectInstance> list = new ArrayList<>();
		if (mc().player != null) {
			for (MobEffectInstance e : mc().player.getActiveEffects()) {
				if (hud().potionHudAmbient.get() && e.isAmbient()) {
					continue;
				}
				list.add(e);
			}
		}
		list.sort(Comparator.comparingInt(e -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
		return list;
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		List<MobEffectInstance> effects = effects(editing);
		if (effects.isEmpty()) {
			setSize(0, 0);
			return;
		}
		if (hud.potionHudCompact.get()) {
			drawCompact(g, effects);
		} else {
			drawFull(g, effects);
		}
	}

	private void drawCompact(GuiGraphicsExtractor g, List<MobEffectInstance> effects) {
		int width = effects.size() * (ICON + 2);
		setSize(width, ICON);
		Render2D.roundedRect(g, getX(), getY(), width, ICON, 3, Theme.hudBg(hud().potionHudBg.get()));
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

	private void drawFull(GuiGraphicsExtractor g, List<MobEffectInstance> effects) {
		int textW = 0;
		for (MobEffectInstance e : effects) {
			textW = Math.max(textW, Render2D.width(title(e)));
			textW = Math.max(textW, Render2D.width(time(e)));
		}
		textW = Math.min(textW, MAX_TEXT);
		int width = ICON + 4 + textW + 6;
		int height = effects.size() * ROW + 2;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud().potionHudBg.get()));

		int y = getY() + 2;
		for (MobEffectInstance e : effects) {
			int alpha = pulse(e, 255);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(e.getEffect()),
					getX() + 2, y, ICON, ICON, ARGB.white(alpha));
			int tx = getX() + ICON + 6;
			Render2D.text(g, title(e), tx, y + 1, ColorUtil.withAlpha(Theme.text, alpha));
			Render2D.text(g, time(e), tx, y + 10, ColorUtil.withAlpha(Theme.hudAccent(0.5f), alpha));
			y += ROW;
		}
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
