package unlucky.utility.client.util;

/**
 * Extra per-item facts ItemPhysics needs at submit time that the vanilla
 * {@code ItemEntityRenderState} doesn't carry: whether the item has settled, how
 * fast it's moving, and a stable per-entity seed so each one lands at its own
 * angle instead of the whole pile facing the same way.
 *
 * <p>Implemented as a duck interface on the render state (26.2 splits extract
 * from submit, so the entity itself is long out of scope by the time we draw).
 */
public interface ItemPhysicsData {
	boolean unlucky$onGround();

	void unlucky$setOnGround(boolean onGround);

	float unlucky$speed();

	void unlucky$setSpeed(float speed);

	int unlucky$seed();

	void unlucky$setSeed(int seed);
}
