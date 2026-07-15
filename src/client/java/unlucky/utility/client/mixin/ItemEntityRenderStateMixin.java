package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import unlucky.utility.client.util.ItemPhysicsData;

/** Carries ItemPhysics' extra state from extract (entity in scope) to submit (not). */
@Mixin(ItemEntityRenderState.class)
public class ItemEntityRenderStateMixin implements ItemPhysicsData {
	@Unique
	private boolean unlucky$onGround;
	@Unique
	private float unlucky$speed;
	@Unique
	private int unlucky$seed;

	@Override
	public boolean unlucky$onGround() {
		return unlucky$onGround;
	}

	@Override
	public void unlucky$setOnGround(boolean onGround) {
		this.unlucky$onGround = onGround;
	}

	@Override
	public float unlucky$speed() {
		return unlucky$speed;
	}

	@Override
	public void unlucky$setSpeed(float speed) {
		this.unlucky$speed = speed;
	}

	@Override
	public int unlucky$seed() {
		return unlucky$seed;
	}

	@Override
	public void unlucky$setSeed(int seed) {
		this.unlucky$seed = seed;
	}
}
