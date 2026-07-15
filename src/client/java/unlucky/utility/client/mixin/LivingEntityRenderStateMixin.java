package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import unlucky.utility.client.util.ChamsRenderState;

/** Carries the chams tint from extractRenderState (has the entity) to submit (has the model). */
@Mixin(LivingEntityRenderState.class)
public class LivingEntityRenderStateMixin implements ChamsRenderState {
	@Unique
	private int unlucky$chamsColor;
	@Unique
	private int unlucky$spinOutlineColor;
	@Unique
	private float unlucky$spinOutlineYaw;

	@Unique
	private int unlucky$popColor;

	@Override
	public void unlucky$setPopColor(int color) {
		this.unlucky$popColor = color;
	}

	@Override
	public int unlucky$getPopColor() {
		return this.unlucky$popColor;
	}

	@Override
	public void unlucky$setChamsColor(int color) {
		this.unlucky$chamsColor = color;
	}

	@Override
	public int unlucky$getChamsColor() {
		return this.unlucky$chamsColor;
	}

	@Override
	public void unlucky$setSpinOutline(int color, float realYaw) {
		this.unlucky$spinOutlineColor = color;
		this.unlucky$spinOutlineYaw = realYaw;
	}

	@Override
	public int unlucky$getSpinOutlineColor() {
		return this.unlucky$spinOutlineColor;
	}

	@Override
	public float unlucky$getSpinOutlineYaw() {
		return this.unlucky$spinOutlineYaw;
	}
}
