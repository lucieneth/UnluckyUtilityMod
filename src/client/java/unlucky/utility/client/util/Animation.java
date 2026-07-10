package unlucky.utility.client.util;

/**
 * A time based animation running between 0 and 1 that can reverse mid-flight
 * without jumping.
 */
public class Animation {
	private final long duration;
	private final Easing easing;
	private boolean forward;
	private long start;

	public Animation(long durationMs, boolean startFinishedForward, Easing easing) {
		this.duration = durationMs;
		this.easing = easing;
		this.forward = startFinishedForward;
		// start far enough in the past that the animation begins settled
		this.start = System.currentTimeMillis() - durationMs;
	}

	public void setDirection(boolean forward) {
		if (this.forward == forward) {
			return;
		}
		long now = System.currentTimeMillis();
		double progress = linearProgress(now);
		this.forward = forward;
		// keep the visual position continuous when reversing
		this.start = now - (long) ((1.0 - progress) * duration);
	}

	public boolean direction() {
		return forward;
	}

	/** Raw progress towards the current direction's target, 0..1. */
	private double linearProgress(long now) {
		double t = Math.clamp((now - start) / (double) duration, 0.0, 1.0);
		return t;
	}

	/** Eased value, 0 = fully reversed, 1 = fully forward. */
	public float value() {
		double t = linearProgress(System.currentTimeMillis());
		double directional = forward ? t : 1.0 - t;
		return (float) easing.apply(directional);
	}

	/** True when reversed and settled at 0 — safe to skip rendering. */
	public boolean isCollapsed() {
		return !forward && linearProgress(System.currentTimeMillis()) >= 1.0;
	}
}
