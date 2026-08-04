package unlucky.utility.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.client.ThemeModule;

/**
 * Hands out the one blur a frame is allowed.
 *
 * <p>{@code GuiRenderState.blurBeforeThisStratum} records a single stratum per frame and
 * throws {@code IllegalStateException: Can only blur once per frame} on a second call, so
 * two features that each want a blurred backdrop take the game down rather than share.
 * Everything that blurs asks {@link #claim} instead, and a second asker quietly goes
 * without — a flat panel is a visual nit, a crash report is not.
 *
 * <p>Which one goes without is not arbitrary. The blur is applied to everything drawn
 * <i>below</i> the stratum that claimed it, and the HUD extracts a stratum earlier than
 * the screen over it: a HUD claim catches the world alone, a screen claim catches the
 * world and the whole HUD with it. One claim genuinely cannot serve both — taken at the
 * screen's stratum it would smear the HUD widget's own text, taken at the HUD's it would
 * leave the menu backdrop sharp. So the HUD asks {@link #screenWillClaim} and stands
 * down, which costs almost nothing: every client screen bar the Future ClickGUI blurs the
 * whole frame anyway, and a widget's own blurred rectangle under that is invisible.
 */
public final class FrameBlur {
	private static boolean claimed;

	private FrameBlur() {
	}

	/** Reopens the claim. Called once per frame from {@code GuiBlurMixin}. */
	public static void beginFrame() {
		claimed = false;
	}

	/**
	 * Blurs everything below the caller's stratum. Returns false, having done nothing, if
	 * this frame's blur is already spoken for.
	 */
	public static boolean claim(GuiGraphicsExtractor graphics) {
		if (claimed) {
			return false;
		}
		claimed = true;
		graphics.blurBeforeThisStratum();
		return true;
	}

	/**
	 * True when the open screen is about to take the frame's blur for its own backdrop.
	 * Anything extracting before the screen has to ask this; anything extracting after it
	 * can simply {@link #claim} and be told no.
	 */
	public static boolean screenWillClaim() {
		if (!(Minecraft.getInstance().gui.screen() instanceof BlursBackground)) {
			return false;
		}
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		return theme != null && theme.blur.get();
	}
}
