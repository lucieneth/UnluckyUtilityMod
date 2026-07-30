package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.Level;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Player coordinates, facing (8-way or degrees) and the opposite dimension's coords. */
public class CoordsWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Coords", "Position, facing and opposite-dimension coords", false));
	public final BooleanSetting bg = add(new BooleanSetting("Coords bg", "Backing behind the coordinates", true));
	public final BooleanSetting nether = add(new BooleanSetting("Dimension coords", "Second line with the other dimension's coords", true));
	public final BooleanSetting compact = add(new BooleanSetting("Coords compact", "Show only Y and facing", false));
	public final BooleanSetting degrees = add(new BooleanSetting("Facing degrees", "Show yaw degrees instead of a compass letter", false));

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
		int x = (int) Math.floor(mc().player.getX());
		int y = (int) Math.floor(mc().player.getY());
		int z = (int) Math.floor(mc().player.getZ());

		String facing;
		if (degrees.get()) {
			int deg = (int) ((mc().player.getYRot() % 360 + 360) % 360);
			facing = deg + "°";
		} else {
			float yaw = (mc().player.getYRot() % 360 + 360) % 360;
			facing = DIRS[Math.round(yaw / 45f) % 8];
		}

		java.util.List<TextLine> lines = new java.util.ArrayList<>();
		lines.add(new TextLine(compact.get()
				? "Y " + y + "  [" + facing + "]"
				: "X " + x + "  Y " + y + "  Z " + z + "  [" + facing + "]", Theme.text));
		if (nether.get()) {
			boolean nether = mc().level.dimension() == Level.NETHER;
			lines.add(new TextLine(nether ? "OW " + (x * 8) + ", " + (z * 8) : "Nether " + (x / 8) + ", " + (z / 8),
					Theme.textDim));
		}
		sortBySize(lines, l -> Render2D.width(l.text()));

		int width = 0;
		for (TextLine line : lines) {
			width = Math.max(width, Render2D.width(line.text()));
		}
		width += 10;
		int height = lines.size() * 9 + 4;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(bg.get()));
		for (int i = 0; i < lines.size(); i++) {
			TextLine line = lines.get(i);
			Render2D.text(g, line.text(), alignedX(Render2D.width(line.text()), 5), getY() + 3 + i * 9, line.color());
		}
	}
}
