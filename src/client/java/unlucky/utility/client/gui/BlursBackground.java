package unlucky.utility.client.gui;

/**
 * Marks a screen that blurs the frame behind its own backdrop.
 *
 * <p>The blur is a single frame-wide resource ({@link FrameBlur}), and the HUD extracts
 * before any screen does — so it has to know, before it draws, whether the screen on top
 * of it is going to want the blur. That question is this interface.
 */
public interface BlursBackground {
}
