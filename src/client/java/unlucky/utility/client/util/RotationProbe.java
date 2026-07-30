package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;

/**
 * What the renderer actually did to the local player's pose, last frame.
 *
 * <p>Exists because three fixes to the third-person silent rotation were shipped on
 * reasoning that all looked right on paper and all failed in play. The chain is
 * request -&gt; pose -&gt; render state -&gt; model, every link plausible and none of them
 * observable; {@code .rot} makes each link say what it saw, so the broken one names
 * itself instead of being guessed at.
 */
public final class RotationProbe {
	/** Frames the local player's render state was extracted. */
	public static int localFrames;
	/** Frames of those where the spoofed pose was written over it. */
	public static int posedFrames;
	/** The pose vanilla had built, and what we replaced it with, on the last such frame. */
	public static float preBody;
	public static float postBody;
	public static float preYRot;
	public static float postYRot;
	public static float preXRot;
	public static float postXRot;

	private RotationProbe() {
	}

	public static void sawLocal() {
		localFrames++;
	}

	public static void posed(float bodyBefore, float yRotBefore, float xRotBefore,
			float bodyAfter, float yRotAfter, float xRotAfter) {
		posedFrames++;
		preBody = bodyBefore;
		preYRot = yRotBefore;
		preXRot = xRotBefore;
		postBody = bodyAfter;
		postYRot = yRotAfter;
		postXRot = xRotAfter;
	}

	/** Human-readable dump for the {@code .rot} command. */
	public static void report(java.util.function.Consumer<String> out) {
		Minecraft mc = Minecraft.getInstance();
		out.accept("§7rotate: §f" + localFrames + "§7 local frames, §f" + posedFrames
				+ "§7 posed");
		out.accept(String.format("§7 last write: body §f%.1f§7->§f%.1f§7  head §f%.1f§7->§f%.1f"
				+ "§7  pitch §f%.1f§7->§f%.1f", preBody, postBody, preYRot, postYRot,
				preXRot, postXRot));
		out.accept("§7 manager: §f" + RotationManager.debug());
		if (mc.player != null) {
			out.accept(String.format("§7 camera: yaw §f%.1f§7 pitch §f%.1f§7 | entity head §f%.1f"
					+ "§7 body §f%.1f", mc.player.getYRot(), mc.player.getXRot(),
					mc.player.yHeadRot, mc.player.yBodyRot));
		}
		out.accept("§7 camera type: §f" + mc.options.getCameraType());
	}

	/** Zeroes the counters so a run can be measured on its own. */
	public static void reset() {
		localFrames = 0;
		posedFrames = 0;
	}
}
