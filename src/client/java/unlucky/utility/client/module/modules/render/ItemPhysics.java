package unlucky.utility.client.module.modules.render;

import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ItemPhysicsData;

/**
 * Dropped items stop being flat billboards spinning on the spot: they lie down
 * flat on the ground where they land, each at its own angle, and tumble through
 * the air on the way there.
 *
 * <p>Vanilla's {@code ItemEntityRenderer.submit} does exactly two things we care
 * about — a sine bob {@code translate} and a Y-axis spin {@code mulPose}. The
 * mixin redirects both, so the rest of the submit pipeline (models, bundles,
 * stack-count copies, lighting) is untouched.
 */
public class ItemPhysics extends Module {
	public final BooleanSetting flat = add(new BooleanSetting("Lie flat", "Rest flat on the ground instead of upright", true));
	public final BooleanSetting tumble = add(new BooleanSetting("Tumble", "Spin end over end while falling", true));
	public final NumberSetting tumbleSpeed = add(new NumberSetting("Tumble speed", "How fast items tumble in the air", 1.0, 0.2, 4.0, 0.1));
	public final BooleanSetting bob = add(new BooleanSetting("Bob", "Keep vanilla's floating bob", false));
	public final NumberSetting height = add(new NumberSetting("Height", "How far off the ground a resting item sits", 0.06, 0.0, 0.5, 0.01));

	public ItemPhysics() {
		super("ItemPhysics", "Dropped items lie flat and tumble as they fall", Category.RENDER);
	}

	/** Vertical offset for a resting item — replaces vanilla's bob unless it's kept on. */
	public float lift(ItemPhysicsData data, float vanillaY) {
		if (bob.get() && !data.unlucky$onGround()) {
			return vanillaY;
		}
		return height.getFloat();
	}

	/**
	 * The item's orientation. Resting: face-up, rotated to its own stable angle so a
	 * dropped stack looks scattered rather than stamped. Airborne: tumbling, the
	 * rate scaled by how fast it's actually moving so a gentle drop doesn't cartwheel.
	 */
	public Quaternionf rotation(ItemPhysicsData data, float ageInTicks) {
		// hash the seed so neighbouring entity ids don't produce neighbouring angles
		int seed = data.unlucky$seed() * 1664525 + 1013904223;
		float yaw = Math.floorMod(seed >> 8, 360);

		Quaternionf q = new Quaternionf();
		q.rotateY(yaw * Mth.DEG_TO_RAD);
		if (!flat.get()) {
			return q; // upright, but at least each item has its own facing
		}
		if (data.unlucky$onGround() || !tumble.get()) {
			q.rotateX(90.0f * Mth.DEG_TO_RAD); // lay it on its face
			return q;
		}
		float rate = tumbleSpeed.getFloat() * (1.0f + Math.min(data.unlucky$speed() * 8.0f, 4.0f));
		float spin = ageInTicks * 12.0f * rate;
		q.rotateX((90.0f + spin) * Mth.DEG_TO_RAD);
		q.rotateZ(spin * 0.4f * Mth.DEG_TO_RAD);
		return q;
	}
}
