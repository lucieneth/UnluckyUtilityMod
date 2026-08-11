package unlucky.utility.client.module.modules.movement;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;

/** Direct or momentum-preserving horizontal movement with explicit safety guards. */
public class Speed extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Flat sets speed directly; Strafe eases toward it.",
			"Flat", "Flat", "Strafe"));
	public final NumberSetting groundSpeed = add(new NumberSetting("Ground speed", "Blocks per second while grounded",
			8.0, 1.0, 30.0, 0.5));
	public final NumberSetting airSpeed = add(new NumberSetting("Air speed", "Blocks per second while airborne",
			8.0, 1.0, 30.0, 0.5));
	public final NumberSetting acceleration = add(new NumberSetting("Acceleration",
			"How quickly Strafe reaches its target speed", 0.35, 0.05, 1.0, 0.05), () -> mode.is("Strafe"));
	public final NumberSetting deceleration = add(new NumberSetting("Deceleration",
			"How quickly Strafe releases horizontal speed", 0.35, 0.05, 1.0, 0.05), () -> mode.is("Strafe"));
	public final BooleanSetting autoJump = add(new BooleanSetting("Auto jump", "Jump while moving on the ground", false));
	public final BooleanSetting keepSprinting = add(new BooleanSetting("Keep sprinting", "Request sprint while Speed moves", true));
	public final BooleanSetting inLiquids = add(new BooleanSetting("In liquids", "Allow Speed while swimming or in lava", false));
	public final BooleanSetting whileSneaking = add(new BooleanSetting("While sneaking", "Allow Speed while sneak is held", false));
	public final BooleanSetting whileUsing = add(new BooleanSetting("While using item", "Allow Speed while using an item", false));
	public final BooleanSetting whileInGui = add(new BooleanSetting("While in GUI", "Allow Speed with a screen open", false));
	public final BooleanSetting whileRiding = add(new BooleanSetting("While riding", "Allow Speed while mounted", false));
	public final BooleanSetting whileElytra = add(new BooleanSetting("While elytra flying", "Allow Speed while gliding", false));
	public final BooleanSetting onIce = add(new BooleanSetting("On ice", "Allow Speed on ice", false));
	public final BooleanSetting onSoulSand = add(new BooleanSetting("On soul sand", "Allow Speed on soul sand", false));
	public final BooleanSetting onHoney = add(new BooleanSetting("On honey", "Allow Speed on honey", false));
	public final BooleanSetting preserveVertical = add(new BooleanSetting("Preserve vertical velocity",
			"Never replace jump or fall velocity", true));
	public final BooleanSetting disableOnCorrection = add(new BooleanSetting("Disable on correction",
			"Turn off after a server position correction", true));

	public Speed() {
		super("Speed", "Move faster than normal", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || !allowed()) {
			return;
		}
		Vec3 velocity = mc().player.getDeltaMovement();
		if (!MoveUtil.hasInput(mc().player)) {
			if (mode.is("Strafe")) {
				double retained = Math.max(0.0, 1.0 - deceleration.get());
				mc().player.setDeltaMovement(velocity.x * retained,
						preserveVertical.get() ? velocity.y : 0.0, velocity.z * retained);
			}
			return;
		}
		if (keepSprinting.get()) mc().player.setSprinting(true);
		if (autoJump.get() && mc().player.onGround()) mc().player.jumpFromGround();
		Vec3 direction = MoveUtil.inputDirection(mc().player);
		if (direction.lengthSqr() < 1.0e-6) {
			return;
		}
		double perTick = (mc().player.onGround() ? groundSpeed.get() : airSpeed.get()) / 20.0;
		Vec3 horizontal = new Vec3(direction.x * perTick, 0, direction.z * perTick);
		if (mode.is("Strafe")) {
			double blend = acceleration.get();
			horizontal = new Vec3(velocity.x, 0, velocity.z).lerp(horizontal, blend);
		}
		mc().player.setDeltaMovement(horizontal.x, preserveVertical.get() ? velocity.y : 0.0, horizontal.z);
	}

	/** Invoked from the existing correction listener; a correction is a clear stop signal. */
	public void onCorrection() {
		if (isEnabled() && disableOnCorrection.get()) setEnabled(false);
	}

	private boolean allowed() {
		var player = mc().player;
		if (mc().gui.screen() != null && !whileInGui.get()) return false;
		if ((player.isInWater() || player.isInLava()) && !inLiquids.get()) return false;
		if (player.isShiftKeyDown() && !whileSneaking.get()) return false;
		if (player.isUsingItem() && !whileUsing.get()) return false;
		if (player.isPassenger() && !whileRiding.get()) return false;
		if (player.isFallFlying() && !whileElytra.get()) return false;
		var state = mc().level.getBlockState(player.blockPosition().below());
		if (state.is(BlockTags.ICE) && !onIce.get()) return false;
		if (state.is(Blocks.SOUL_SAND) && !onSoulSand.get()) return false;
		return !state.is(Blocks.HONEY_BLOCK) || onHoney.get();
	}
}
