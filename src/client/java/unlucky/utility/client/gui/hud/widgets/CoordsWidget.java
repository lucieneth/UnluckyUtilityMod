package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.Level;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Player coordinates, facing (8-way or degrees) and the opposite dimension's coords. */
public class CoordsWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Coords", "Position, facing and opposite-dimension coords", false));
	public final BooleanSetting bg = add(new BooleanSetting("Coords bg", "Backing behind the coordinates", true));
	public final BooleanSetting nether = add(new BooleanSetting("Dimension coords", "Second line with the other dimension's coords", true));
	public final BooleanSetting compact = add(new BooleanSetting("Coords compact", "Show only Y and facing", false));
	public final BooleanSetting degrees = add(new BooleanSetting("Facing degrees", "Show yaw degrees instead of a compass letter", false));
	public final ModeSetting layout = add(new ModeSetting("Coords layout", "Full, compact, or one-line coordinates", "Full", "Full", "Compact", "Single line"));
	public final NumberSetting decimals = add(new NumberSetting("Coords decimals", "Decimal precision for coordinates", 0, 0, 2, 1));
	public final BooleanSetting axis = add(new BooleanSetting("Facing axis", "Show the world axis beside the direction", true));
	public final BooleanSetting chunk = add(new BooleanSetting("Chunk coordinates", "Show the current chunk coordinates", false));
	public final BooleanSetting dimension = add(new BooleanSetting("Dimension name", "Show the current dimension", false));

	private static final String[] DIRS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	public CoordsWidget() {
		super("Coords");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 0.92);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		if (mc().player == null || mc().level == null) {
			setSize(0, 0);
			return;
		}
		double x = mc().player.getX();
		double y = mc().player.getY();
		double z = mc().player.getZ();

		String facing;
		if (degrees.get()) {
			int deg = (int) ((mc().player.getYRot() % 360 + 360) % 360);
			facing = deg + "°";
		} else {
			float yaw = (mc().player.getYRot() % 360 + 360) % 360;
			facing = DIRS[Math.round(yaw / 45f) % 8];
		}
		if (axis.get()) {
			String dir = DIRS[Math.round(((mc().player.getYRot() % 360 + 360) % 360) / 45f) % 8];
			facing += " " + switch (dir) {
				case "N" -> "-Z"; case "S" -> "+Z"; case "E" -> "+X"; case "W" -> "-X";
				case "NE" -> "+X/-Z"; case "NW" -> "-X/-Z"; case "SE" -> "+X/+Z"; default -> "-X/+Z";
			};
		}

		java.util.List<TextLine> lines = new java.util.ArrayList<>();
		boolean compactMode = !horizontalLayout() && !verticalLayout() && (compact.get() || layout.is("Compact"));
		boolean singleLine = horizontalLayout() || (!verticalLayout() && layout.is("Single line"));
		String xyz = "X " + number(x) + "  Y " + number(y) + "  Z " + number(z);
		lines.add(new TextLine(compactMode ? "Y " + number(y) + "  [" + facing + "]" : xyz + "  [" + facing + "]", Theme.text));
		if (nether.get()) {
			boolean inNether = mc().level.dimension() == Level.NETHER;
			String converted = inNether ? "OW " + number(x * 8) + ", " + number(z * 8) : "Nether " + number(x / 8) + ", " + number(z / 8);
			if (singleLine) {
				lines.set(0, new TextLine(lines.getFirst().text() + "  |  " + converted, Theme.text));
			} else {
				lines.add(new TextLine(converted, Theme.textDim));
			}
		}
		if (chunk.get()) {
			String chunkText = "Chunk " + ((int) Math.floor(x) >> 4) + ", " + ((int) Math.floor(z) >> 4);
			if (singleLine) lines.set(0, new TextLine(lines.getFirst().text() + "  |  " + chunkText, Theme.text));
			else lines.add(new TextLine(chunkText, Theme.textDim));
		}
		if (dimension.get()) {
			String dimensionText = mc().level.dimension().identifier().toString();
			if (singleLine) lines.set(0, new TextLine(lines.getFirst().text() + "  |  " + dimensionText, Theme.text));
			else lines.add(new TextLine(dimensionText, Theme.textDim));
		}
		sortBySize(lines, l -> Render2D.width(l.text()));

		int width = 0;
		for (TextLine line : lines) {
			width = Math.max(width, Render2D.width(line.text()));
		}
		width += 10;
		int rowHeight = Math.max(9, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int height = lines.size() * rowHeight + 4;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		for (int i = 0; i < lines.size(); i++) {
			TextLine line = lines.get(i);
			Render2D.text(g, line.text(), alignedX(Render2D.width(line.text()), 5), getY() + 3 + i * rowHeight, line.color());
		}
	}

	private String number(double value) {
		return String.format(java.util.Locale.ROOT, "%." + decimals.getInt() + "f", value);
	}
}
