package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Horizontal movement speed in blocks/second (or km/h), sampled from per-tick
 * position deltas and smoothed, with an optional sparkline of recent speed.
 */
public class SpeedometerWidget extends HudWidget {
	private static final int SPARK_W = 64;
	private static final int SPARK_H = 14;

	private boolean init;
	private double lastX;
	private double lastZ;
	private long lastChangeMs;
	private float displayed;
	private final float[] spark = new float[48];
	private int sparkCount;
	private long lastSparkMs;

	public SpeedometerWidget() {
		super("Speedometer");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().speed.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 0.84);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		if (mc().player == null) {
			setSize(0, 0);
			return;
		}
		HudModule hud = hud();
		long now = System.currentTimeMillis();
		double x = mc().player.getX();
		double z = mc().player.getZ();
		if (!init) {
			init = true;
			lastX = x;
			lastZ = z;
			lastChangeMs = now;
		}
		if (x != lastX || z != lastZ) {
			double dist = Math.hypot(x - lastX, z - lastZ);
			double dt = (now - lastChangeMs) / 1000.0;
			lastX = x;
			lastZ = z;
			lastChangeMs = now;
			if (dt > 0 && dist < 40) { // ignore teleports
				float inst = (float) (dist / dt);
				displayed += (inst - displayed) * 0.35f;
			}
		} else if (now - lastChangeMs > 200) {
			displayed += (0 - displayed) * 0.2f; // decay toward zero when standing still
		}
		if (now - lastSparkMs > 60) {
			lastSparkMs = now;
			if (sparkCount < spark.length) {
				spark[sparkCount++] = displayed;
			} else {
				System.arraycopy(spark, 1, spark, 0, spark.length - 1);
				spark[spark.length - 1] = displayed;
			}
		}

		boolean kmh = hud.speedUnit.is("km/h");
		float value = kmh ? displayed * 3.6f : displayed;
		String unit = kmh ? " km/h" : " b/s";
		String text = String.format("%." + hud.speedDecimals.getInt() + "f", value) + unit;
		boolean showSpark = hud.speedSpark.get();

		int width = Math.max(Render2D.width(text), showSpark ? SPARK_W : 0) + 10;
		int height = 13 + (showSpark ? SPARK_H : 0);
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud.speedBg.get()));
		Render2D.text(g, text, alignedX(Render2D.width(text), 5), getY() + 3, Theme.text);

		if (showSpark && sparkCount > 1) {
			float max = 0.01f;
			for (int i = 0; i < sparkCount; i++) {
				max = Math.max(max, spark[i]);
			}
			int baseY = getY() + height - 3;
			float step = (float) (width - 10) / (sparkCount - 1);
			for (int i = 1; i < sparkCount; i++) {
				float x0 = getX() + 5 + (i - 1) * step;
				float x1 = getX() + 5 + i * step;
				float y0 = baseY - spark[i - 1] / max * SPARK_H;
				float y1 = baseY - spark[i] / max * SPARK_H;
				Render2D.line(g, x0, y0, x1, y1, 1.0f, ColorUtil.withAlpha(Theme.hudAccent((float) i / sparkCount), 220));
			}
		}
	}
}
