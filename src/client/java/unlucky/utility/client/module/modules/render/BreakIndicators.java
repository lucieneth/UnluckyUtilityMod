package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.MiningTracker;
import unlucky.utility.client.util.Render3D;

/**
 * Shows how far along a block break is — yours, and everyone else's.
 *
 * <p><b>It visualises progress and never changes it.</b> That separation is the whole design:
 * SpeedMine decides how fast a block comes apart, this draws what SpeedMine decided, and both
 * read the same {@link MiningTracker}. A break indicator that computed its own progress would
 * eventually disagree with the module driving the break, and the player would have no way to
 * tell which of the two was lying — the picture is meant to be evidence, so it has to come from
 * the same number the action does.
 *
 * <p>Other players' breaks come from vanilla's own {@code destructionProgress}, which is what
 * the cracking overlay is drawn from. That is a stage 0–9, not a fraction: the server sends ten
 * discrete steps and there is no finer signal to have. Local progress is the real fraction, so
 * the two sources are deliberately not blended into one number.
 *
 * <p>Progress is never inferred from repeated render ticks. A frame is not a unit of mining
 * time, and a client at 30fps would show a different bar from one at 240 for the same block.
 */
public class BreakIndicators extends Module {
	/** Vanilla's break animation has ten stages, 0..9. */
	private static final float STAGES = 10.0f;

	public final BooleanSetting localSource = add(new BooleanSetting("Local",
			"Show the block you are breaking yourself, however it is being driven", true));
	public final BooleanSetting othersSource = add(new BooleanSetting("Other players",
			"Show blocks other players are breaking, from the server's own animation stages", true));

	public final ModeSetting shape = add(new ModeSetting("Shape",
			"Outline, filled faces, or both", "Both", "Outline", "Fill", "Both"));
	public final ModeSetting progressStyle = add(new ModeSetting("Progress style",
			"Shrink closes the box in as the block breaks; Grow opens it out; Static keeps it whole",
			"Shrink", "Shrink", "Grow", "Static"));

	public final ColorSetting startSide = add(new ColorSetting("Start side color",
			"Fill colour at 0%", 0x305CD6FF), () -> !shape.is("Outline"));
	public final ColorSetting endSide = add(new ColorSetting("End side color",
			"Fill colour at 100%", 0x605CD6FF), () -> !shape.is("Outline"));
	public final ColorSetting startLine = add(new ColorSetting("Start line color",
			"Outline colour at 0%", 0xFF5CD6FF), () -> !shape.is("Fill"));
	public final ColorSetting endLine = add(new ColorSetting("End line color",
			"Outline colour at 100%", 0xFFFFB347), () -> !shape.is("Fill"));
	public final NumberSetting lineWidth = add(new NumberSetting("Line width",
			"Outline width", 1.5, 0.5, 5.0, 0.1), () -> !shape.is("Fill"));

	public final ModeSetting text = add(new ModeSetting("Text",
			"Label drawn at the block", "Percent", "Off", "Percent", "Time", "Tool"));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw indicators that are behind terrain", true));
	public final NumberSetting completionFade = add(new NumberSetting("Completion fade",
			"Milliseconds a finished or abandoned break stays on screen", 250, 0, 2000, 25));

	/** Finished or abandoned breaks, still fading. Position to the moment they ended. */
	private final Map<BlockPos, Long> fading = new HashMap<>();
	/** Whether the local break was still live last tick, so its ending can be noticed once. */
	private BlockPos lastLocalTarget;

	public BreakIndicators() {
		super("BreakIndicators", "Shows block-break progress for you and other players",
				Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onDisable() {
		fading.clear();
		lastLocalTarget = null;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			onDisable();
			return;
		}
		expireFades();
		if (localSource.get()) {
			drawLocal();
		}
		if (othersSource.get()) {
			drawOthers();
		}
		drawFades();
	}

	/**
	 * The local break, read from the shared tracker rather than from vanilla directly.
	 *
	 * <p>Which means it covers a packet break the same way it covers a vanilla one, and covers
	 * a module-driven break the same way it covers the player's own — the tracker already
	 * normalised all four into one record, so there is nothing here that knows the difference.
	 */
	private void drawLocal() {
		BlockPos target = MiningTracker.target();
		if (target == null) {
			lastLocalTarget = null;
			return;
		}
		if (!MiningTracker.isBreaking()) {
			// Terminal: hand it to the fade, once, and stop drawing it live.
			if (target.equals(lastLocalTarget)) {
				fading.putIfAbsent(target, System.currentTimeMillis());
				lastLocalTarget = null;
			}
			return;
		}
		lastLocalTarget = target;
		draw(target, MiningTracker.progress(), 1.0f, localLabel());
	}

	/** Everyone else's, from vanilla's ten-stage animation state. */
	private void drawOthers() {
		Long2ObjectMap<SortedSet<BlockDestructionProgress>> all = mc().level.destructionProgress();
		BlockPos own = MiningTracker.target();
		for (SortedSet<BlockDestructionProgress> set : all.values()) {
			for (BlockDestructionProgress entry : set) {
				BlockPos pos = entry.getPos();
				// Our own break is already drawn from the real fraction above; drawing it again
				// from the coarse stage would put two boxes on one block, a stage apart.
				if (pos.equals(own)) {
					continue;
				}
				draw(pos, Mth.clamp(entry.getProgress() / STAGES, 0.0f, 1.0f), 1.0f, "");
			}
		}
	}

	private void expireFades() {
		long fade = completionFade.getInt();
		long now = System.currentTimeMillis();
		fading.entrySet().removeIf(entry -> now - entry.getValue() >= Math.max(1L, fade));
	}

	private void drawFades() {
		long fade = completionFade.getInt();
		if (fade <= 0) {
			fading.clear();
			return;
		}
		long now = System.currentTimeMillis();
		for (Map.Entry<BlockPos, Long> entry : fading.entrySet()) {
			float life = 1.0f - (float) (now - entry.getValue()) / fade;
			draw(entry.getKey(), 1.0f, Mth.clamp(life, 0.0f, 1.0f), "");
		}
	}

	/**
	 * One indicator.
	 *
	 * @param progress 0..1
	 * @param alpha    a whole-indicator multiplier, for the completion fade
	 */
	private void draw(BlockPos pos, float progress, float alpha, String label) {
		BlockState state = mc().level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}
		AABB box = shapeFor(pos, state, progress);
		int fill = shape.is("Outline") ? 0
				: fade(ColorUtil.lerp(startSide.get(), endSide.get(), progress), alpha);
		int outline = shape.is("Fill") ? 0
				: fade(ColorUtil.lerp(startLine.get(), endLine.get(), progress), alpha);
		Render3D.box(box, outline, lineWidth.getFloat(), fill, throughWalls.get());
		if (!label.isEmpty()) {
			Render3D.blockLabel(label, pos, fade(endLine.get(), alpha), 1.0f);
		}
	}

	/**
	 * The box, sized by progress.
	 *
	 * <p>Built from the block's own outline shape rather than a unit cube, so a slab or a fence
	 * shows a bar the shape of what is actually being broken. Shrink and Grow scale it about
	 * the centre; the minimum keeps a nearly-finished box from collapsing into an invisible dot
	 * exactly when the player most wants to see it.
	 */
	private AABB shapeFor(BlockPos pos, BlockState state, float progress) {
		AABB base = state.getShape(mc().level, pos).isEmpty()
				? new AABB(pos)
				: state.getShape(mc().level, pos).bounds().move(pos);
		if (progressStyle.is("Static")) {
			return base;
		}
		float scale = progressStyle.is("Shrink") ? 1.0f - progress * 0.85f : 0.15f + progress * 0.85f;
		double cx = (base.minX + base.maxX) / 2.0;
		double cy = (base.minY + base.maxY) / 2.0;
		double cz = (base.minZ + base.maxZ) / 2.0;
		return new AABB(
				cx + (base.minX - cx) * scale, cy + (base.minY - cy) * scale, cz + (base.minZ - cz) * scale,
				cx + (base.maxX - cx) * scale, cy + (base.maxY - cy) * scale, cz + (base.maxZ - cz) * scale);
	}

	private static int fade(int color, float alpha) {
		return alpha >= 1.0f ? color : ColorUtil.multiplyAlpha(color, alpha);
	}

	private String localLabel() {
		return switch (text.get()) {
			case "Percent" -> Math.round(MiningTracker.progress() * 100.0f) + "%";
			case "Time" -> timeLabel();
			case "Tool" -> MiningTracker.tool().isEmpty()
					? "hand" : MiningTracker.tool().getHoverName().getString();
			default -> "";
		};
	}

	/**
	 * Seconds left, from the tracker's estimate.
	 *
	 * <p>A dash rather than a number when the estimate is unavailable — a block that cannot be
	 * broken with what is in hand has no honest time to show, and showing a very large one
	 * reads as "nearly there, keep going".
	 */
	private String timeLabel() {
		int remaining = MiningTracker.remainingTicks();
		return remaining < 0 ? "—" : String.format("%.1fs", remaining / 20.0f);
	}

	/** Positions currently drawn, for tests and the debug read-out. */
	public List<BlockPos> activeIndicators() {
		List<BlockPos> out = new ArrayList<>(fading.keySet());
		if (MiningTracker.isBreaking() && MiningTracker.target() != null) {
			out.add(MiningTracker.target());
		}
		return out;
	}
}
