package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Horizontal movement speed in blocks/second (or km/h), sampled from per-tick
 * position deltas and smoothed, with an optional sparkline of recent speed.
 */
public class SpeedometerWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Speedometer", "Horizontal movement speed", false));
	public final BooleanSetting bg = add(new BooleanSetting("Speed bg", "Backing behind the speedometer", true));
	public final ModeSetting units = add(new ModeSetting("Speed unit", "Speed units", "b/s", "b/s", "km/h"));
	public final BooleanSetting sparkline = add(new BooleanSetting("Speed sparkline", "Mini graph of recent speed", true));
	public final NumberSetting decimals = add(new NumberSetting("Speed decimals", "Decimal places on the speed value", 1, 0, 2, 1));
	public final ModeSetting layout = add(new ModeSetting("Speed layout", "Normal readout or compact value", "Normal", "Normal", "Compact"));
	public final ModeSetting graphStyle = add(new ModeSetting("Speed graph style", "Line or bar history graph", "Line", "Line", "Bars"));
	public final ModeSetting graphScale = add(new ModeSetting("Speed graph scale", "Automatically scale the graph or use a fixed maximum", "Auto", "Auto", "Fixed"));
	public final NumberSetting graphMaximum = add(new NumberSetting("Speed graph maximum", "Fixed graph maximum in blocks per second", 20, 2, 100, 1));
	public final NumberSetting smoothing = add(new NumberSetting("Speed smoothing", "How quickly the readout follows movement", 35, 5, 100, 5));

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
		graphMaximum.showWhen(() -> graphScale.is("Fixed"));
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
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
				displayed += (inst - displayed) * (smoothing.getFloat() / 100.0f);
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

		boolean kmh = units.is("km/h");
		float previewed = editing && HudManager.isPreviewData() && displayed < 0.05f ? 4.2f : displayed;
		float value = kmh ? previewed * 3.6f : previewed;
		String unit = layout.is("Compact") ? "" : kmh ? " km/h" : " b/s";
		String text = String.format("%." + decimals.getInt() + "f", value) + unit;
		boolean showSpark = sparkline.get();

		int width = Math.max(Render2D.width(text), showSpark ? SPARK_W : 0) + 10;
		int textHeight = Math.max(13, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 4);
		int height = textHeight + (showSpark ? SPARK_H : 0);
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		Render2D.text(g, text, alignedX(Render2D.width(text), 5), getY() + 3, Theme.text);

		if (showSpark && sparkCount > 1) {
			float max = graphScale.is("Fixed") ? graphMaximum.getFloat() : 0.01f;
			if (graphScale.is("Auto")) for (int i = 0; i < sparkCount; i++) max = Math.max(max, spark[i]);
			int baseY = getY() + height - 3;
			float step = (float) (width - 10) / (sparkCount - 1);
			for (int i = 1; i < sparkCount; i++) {
				float x0 = getX() + 5 + (i - 1) * step;
				float x1 = getX() + 5 + i * step;
				float y0 = baseY - spark[i - 1] / max * SPARK_H;
				float y1 = baseY - spark[i] / max * SPARK_H;
				int color = ColorUtil.withAlpha(accentAt(i, sparkCount), 220);
				if (graphStyle.is("Bars")) {
					Render2D.rect(g, Math.round(x1), Math.round(y1), Math.max(1, Math.round(step)), Math.max(1, Math.round(baseY - y1)), color);
				} else {
					Render2D.line(g, x0, y0, x1, y1, 1.0f, color);
				}
			}
		}
	}
}
