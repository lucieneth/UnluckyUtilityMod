package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;

/**
 * Glide like you have an elytra without one equipped: keep the chestplate on,
 * spend no durability. Jump mid-air to start, rockets work as normal.
 *
 * <p><b>Works in singleplayer and LAN. Cannot work on a remote server.</b> That's
 * not a bug to fix later — it's where the state lives. 26.2 gates the whole glide
 * re-check in {@code LivingEntity.updateFallFlying()} behind
 * {@code if (!level().isClientSide())}:
 * <pre>if (!canGlide()) { setSharedFlag(7, false); return; }</pre>
 * The client never stops a glide on its own; it stops because the server said so
 * and synced shared flag 7 back. So a client-only override buys exactly one
 * network round trip of flight — that's the "few frames" — and then you drop.
 *
 * <p>Singleplayer and LAN run that server <i>in this JVM, on this same class</i>,
 * so {@code LivingEntityMixin} does reach it — it just has to recognise the
 * integrated server's {@code ServerPlayer}, which is a different object from our
 * {@code LocalPlayer}. It does now, UUID-matched, and the glide holds.
 *
 * <p>A remote server is a different machine running unmixed vanilla, and it alone
 * decides whether you're gliding. No client can talk it out of that: your
 * equipment is the server's own state, not something we get to assert. Ignoring
 * its flag-7 sync would only desync you into rubber-banding and fall damage. For
 * flight that holds on a real server, use ElytraFly (with an actual elytra),
 * CreativeFlight, or Jetpack.
 */
public class FakeFly extends Module {
	public FakeFly() {
		super("FakeFly", "Elytra flight without an elytra (singleplayer/LAN only)", Category.MOVEMENT);
	}
}
