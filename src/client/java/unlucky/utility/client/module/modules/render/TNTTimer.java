package unlucky.utility.client.module.modules.render;

import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/** Client-only fuse labels and optional ESP for primed TNT. */
public class TNTTimer extends Module {
	public final BooleanSetting esp = add(new BooleanSetting("ESP", "Draw TNT fuse labels", true));
	public final BooleanSetting timer = add(new BooleanSetting("Timer", "Include remaining fuse time", true));
	public final ModeSetting unit = add(new ModeSetting("Unit", "Fuse time unit", "Seconds", "Seconds", "Ticks"));
	public final NumberSetting decimals = add(new NumberSetting("Decimals", "Seconds decimal places", 1, 0, 2, 1));
	public final NumberSetting scale = add(new NumberSetting("Scale", "Label scale", 1.5, 0.5, 3, 0.1));
	public final NumberSetting yOffset = add(new NumberSetting("Y offset", "Blocks above TNT", 1.0, 0, 3, 0.1));
	public final BooleanSetting owner = add(new BooleanSetting("Owner", "Show priming entity", true));
	public final BooleanSetting distance = add(new BooleanSetting("Distance", "Show distance from player", false));
	public final ModeSetting colorMode = add(new ModeSetting("Color", "Fuse gradient or fixed color", "Fuse gradient", "Fuse gradient", "Static"));
	public final ColorSetting staticColor = add(new ColorSetting("Static color", "Fixed label/box color", 0xFFFFAA33), () -> colorMode.is("Static"));
	public final BooleanSetting box = add(new BooleanSetting("Box", "Draw the TNT entity box", false));
	public final BooleanSetting onlyVisible = add(new BooleanSetting("Only visible", "Hide TNT behind blocks", false));

	public TNTTimer() {
		super("TNTTimer", "Shows primed TNT fuse time and owner", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override public void onTick() {
		if (!esp.get() || mc().player == null || mc().level == null) return;
		for (var entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof PrimedTnt tnt) || onlyVisible.get() && !mc().player.hasLineOfSight(tnt)) continue;
			int fuse = Math.max(0, tnt.getFuse());
			int color = colorMode.is("Static") ? staticColor.get() : gradient(fuse);
			StringBuilder label = new StringBuilder("TNT");
			if (timer.get()) label.append(" ").append(format(fuse));
			if (owner.get() && tnt.getOwner() != null) label.append(" · ").append(tnt.getOwner().getName().getString());
			if (distance.get()) label.append(" · ").append((int) mc().player.distanceTo(tnt)).append("m");
			Render3D.blockLabel(label.toString(), tnt.blockPosition().above((int) Math.ceil(yOffset.get())), color, scale.getFloat());
			if (box.get()) {
				AABB bounds = tnt.getBoundingBox().inflate(0.03);
				Render3D.box(bounds, color, 1.5f, ColorUtil.withAlpha(color, 28), true);
			}
		}
	}

	private String format(int fuse) {
		if (unit.is("Ticks")) return fuse + "t";
		return String.format(java.util.Locale.ROOT, "%." + decimals.getInt() + "fs", fuse / 20.0);
	}

	private static int gradient(int fuse) {
		float safe = Math.clamp(fuse / 80.0f, 0, 1);
		int red = 255;
		int green = (int) (70 + safe * 185);
		return 0xFF000000 | red << 16 | green << 8 | 40;
	}
}
