package unlucky.utility.client.module.modules.player;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;

/** Adds fluids to vanilla's ordinary block clip; interaction and distance stay vanilla. */
public class LiquidInteract extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Source only targets full source blocks; Any liquid also targets flowing fluid",
			"Source only", "Source only", "Any liquid"));
	public final BooleanSetting water = add(new BooleanSetting("Water", "Allow water targets", true));
	public final BooleanSetting lava = add(new BooleanSetting("Lava", "Allow lava targets", true));

	public LiquidInteract() {
		super("LiquidInteract", "Lets normal interactions select water and lava",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Called only around the Entity.pick used by LocalPlayer's crosshair raycast. */
	public HitResult pick(Entity source, double range, float partialTick, boolean vanillaFluids,
			Operation<HitResult> original) {
		if (!isEnabled() || source != mc().player || (!water.get() && !lava.get())) {
			return original.call(source, range, partialTick, vanillaFluids);
		}
		Vec3 start = source.getEyePosition(partialTick);
		Vec3 end = start.add(source.getViewVector(partialTick).scale(range));
		ClipContext.Fluid fluid = mode.is("Source only")
				? ClipContext.Fluid.SOURCE_ONLY
				: water.get() && !lava.get() ? ClipContext.Fluid.WATER : ClipContext.Fluid.ANY;
		BlockHitResult hit = source.level().clip(new ClipContext(start, end,
				ClipContext.Block.OUTLINE, fluid, source));
		FluidState state = source.level().getFluidState(hit.getBlockPos());
		if (state.isEmpty() || accepted(state)) return hit;
		// A disabled fluid must remain transparent to picking, exactly as it is in vanilla.
		return original.call(source, range, partialTick, false);
	}

	private boolean accepted(FluidState state) {
		return (water.get() && state.is(FluidTags.WATER))
				|| (lava.get() && state.is(FluidTags.LAVA));
	}
}
