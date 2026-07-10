package unlucky.utility.client.util;

/** Duck-typed accessor mixed into {@code LivingEntityRenderState} to carry the chams tint. */
public interface ChamsRenderState {
	void unlucky$setChamsColor(int color);

	int unlucky$getChamsColor();

	/**
	 * Spinbot's "real player" ghost: a second silhouette re-pointed to the true
	 * facing. Color 0 means don't draw it; the yaw is the real (camera) body yaw.
	 */
	void unlucky$setSpinOutline(int color, float realYaw);

	int unlucky$getSpinOutlineColor();

	float unlucky$getSpinOutlineYaw();
}
