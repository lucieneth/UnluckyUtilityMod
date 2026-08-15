package unlucky.utility.client.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.GroupSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Moves the hands, and only the hands.
 *
 * <p><b>Nothing here can change where you are pointing.</b> Every transform is pushed onto the
 * first-person pose stack immediately before the arm is submitted and popped immediately after,
 * which puts it downstream of everything the game has already decided: the packet yaw and pitch
 * were sent before this frame existed, the hit result was resolved against the camera and not
 * against the model, and reach never consulted the arm at all. That containment is the module's
 * whole safety argument — a first-person transform that leaked into any of those would be a reach
 * or aim change wearing a cosmetic label.
 *
 * <p><b>Push and pop are symmetric or the frame is ruined.</b> The pose stack is shared with
 * everything drawn after the hands, so an unbalanced push does not misplace an arm — it misplaces
 * the rest of the frame. The push is recorded on this module and the pop is unconditional on the
 * same hook's return, so a mid-frame settings change cannot leave the stack one deep.
 *
 * <p><b>The swing controls are visual only.</b> Swing progress feeds the animation and nothing
 * else — the attack was already sent, its timing already fixed. Speeding the animation up makes
 * the arm finish sooner; it does not make you hit faster, and it deliberately cannot.
 */
public class ViewModel extends Module {
	public final BooleanSetting syncHands = add(new BooleanSetting("Sync hands",
			"Mirror the main-hand settings onto the offhand", false));

	public final GroupSetting mainHand = add(new GroupSetting("Main hand",
			"Main-hand transform"));
	public final NumberSetting mainX = add(new NumberSetting("Main-hand X",
			"Sideways translation", 0.0, -2.0, 2.0, 0.05), mainHand::isExpanded);
	public final NumberSetting mainY = add(new NumberSetting("Main-hand Y",
			"Vertical translation", 0.0, -2.0, 2.0, 0.05), mainHand::isExpanded);
	public final NumberSetting mainZ = add(new NumberSetting("Main-hand Z",
			"Depth translation", 0.0, -2.0, 2.0, 0.05), mainHand::isExpanded);
	public final NumberSetting mainScaleX = add(new NumberSetting("Main-hand scale X",
			"Width", 1.0, 0.1, 3.0, 0.05), mainHand::isExpanded);
	public final NumberSetting mainScaleY = add(new NumberSetting("Main-hand scale Y",
			"Height", 1.0, 0.1, 3.0, 0.05), mainHand::isExpanded);
	public final NumberSetting mainScaleZ = add(new NumberSetting("Main-hand scale Z",
			"Depth", 1.0, 0.1, 3.0, 0.05), mainHand::isExpanded);
	public final NumberSetting mainRotX = add(new NumberSetting("Main-hand pitch",
			"Rotation about X", 0.0, -180.0, 180.0, 1.0), mainHand::isExpanded);
	public final NumberSetting mainRotY = add(new NumberSetting("Main-hand yaw",
			"Rotation about Y", 0.0, -180.0, 180.0, 1.0), mainHand::isExpanded);
	public final NumberSetting mainRotZ = add(new NumberSetting("Main-hand roll",
			"Rotation about Z", 0.0, -180.0, 180.0, 1.0), mainHand::isExpanded);

	public final GroupSetting offHand = add(new GroupSetting("Offhand",
			"Offhand transform"));
	public final NumberSetting offX = add(new NumberSetting("Offhand X",
			"Sideways translation", 0.0, -2.0, 2.0, 0.05), this::offhandShown);
	public final NumberSetting offY = add(new NumberSetting("Offhand Y",
			"Vertical translation", 0.0, -2.0, 2.0, 0.05), this::offhandShown);
	public final NumberSetting offZ = add(new NumberSetting("Offhand Z",
			"Depth translation", 0.0, -2.0, 2.0, 0.05), this::offhandShown);
	public final NumberSetting offScaleX = add(new NumberSetting("Offhand scale X",
			"Width", 1.0, 0.1, 3.0, 0.05), this::offhandShown);
	public final NumberSetting offScaleY = add(new NumberSetting("Offhand scale Y",
			"Height", 1.0, 0.1, 3.0, 0.05), this::offhandShown);
	public final NumberSetting offScaleZ = add(new NumberSetting("Offhand scale Z",
			"Depth", 1.0, 0.1, 3.0, 0.05), this::offhandShown);
	public final NumberSetting offRotX = add(new NumberSetting("Offhand pitch",
			"Rotation about X", 0.0, -180.0, 180.0, 1.0), this::offhandShown);
	public final NumberSetting offRotY = add(new NumberSetting("Offhand yaw",
			"Rotation about Y", 0.0, -180.0, 180.0, 1.0), this::offhandShown);
	public final NumberSetting offRotZ = add(new NumberSetting("Offhand roll",
			"Rotation about Z", 0.0, -180.0, 180.0, 1.0), this::offhandShown);

	public final NumberSetting equipProgress = add(new NumberSetting("Equip progress",
			"Multiplier on the equip dip; 0 keeps the item fully raised", 1.0, 0.0, 1.0, 0.05));
	public final BooleanSetting skipSwapAnimation = add(new BooleanSetting("Skip swap animation",
			"Suppress the dip when the held slot changes", false));

	public final ModeSetting swingMode = add(new ModeSetting("Swing mode",
			"Vanilla animates the swing, None leaves the hand still", "Vanilla", "Vanilla", "None"));
	public final NumberSetting swingSpeed = add(new NumberSetting("Swing speed",
			"How fast the animation plays. Visual only — it cannot change how often you hit.",
			1.0, 0.1, 3.0, 0.1), () -> swingMode.is("Vanilla"));

	public final ModeSetting useAnimation = add(new ModeSetting("Use animation",
			"The eat, drink and brush hand jiggle", "Vanilla", "Vanilla", "Hidden"));

	/** Whether we pushed on this arm, so the pop can never be a guess. */
	private boolean pushed;

	public ViewModel() {
		super("ViewModel", "Repositions the first-person hands", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	private boolean offhandShown() {
		return offHand.isExpanded() && !syncHands.get();
	}

	/**
	 * Applies this hand's transform and remembers that it did.
	 *
	 * <p>Order is translate, rotate, scale — the same order every transform editor in the genre
	 * uses, and the one that makes the numbers mean what a player expects: the offsets move the
	 * item where you want it, then the rotation spins it in place rather than swinging it around
	 * the camera.
	 */
	public void push(PoseStack pose, InteractionHand hand) {
		pushed = false;
		if (!isEnabled() || pose == null) {
			return;
		}
		boolean main = hand == InteractionHand.MAIN_HAND || syncHands.get();
		double x = main ? mainX.get() : offX.get();
		double y = main ? mainY.get() : offY.get();
		double z = main ? mainZ.get() : offZ.get();
		double sx = main ? mainScaleX.get() : offScaleX.get();
		double sy = main ? mainScaleY.get() : offScaleY.get();
		double sz = main ? mainScaleZ.get() : offScaleZ.get();
		double rx = main ? mainRotX.get() : offRotX.get();
		double ry = main ? mainRotY.get() : offRotY.get();
		double rz = main ? mainRotZ.get() : offRotZ.get();

		if (x == 0 && y == 0 && z == 0 && sx == 1 && sy == 1 && sz == 1
				&& rx == 0 && ry == 0 && rz == 0) {
			return; // nothing to do; do not spend a stack level on an identity
		}
		pose.pushPose();
		pushed = true;
		pose.translate((float) x, (float) y, (float) z);
		if (rx != 0.0) {
			pose.mulPose(Axis.XP.rotationDegrees((float) rx));
		}
		if (ry != 0.0) {
			pose.mulPose(Axis.YP.rotationDegrees((float) ry));
		}
		if (rz != 0.0) {
			pose.mulPose(Axis.ZP.rotationDegrees((float) rz));
		}
		pose.scale((float) sx, (float) sy, (float) sz);
	}

	/** Unconditionally balances {@link #push}. Reads the flag, never the settings. */
	public void pop(PoseStack pose) {
		if (pushed && pose != null) {
			pose.popPose();
		}
		pushed = false;
	}

	/**
	 * The equip dip, scaled.
	 *
	 * <p>Zero is "fully raised" in vanilla's terms — the argument is how far <em>down</em> the
	 * item is — so both settings drive it toward zero rather than away from it.
	 */
	public float equipProgress(float vanilla) {
		if (!isEnabled()) {
			return vanilla;
		}
		return skipSwapAnimation.get() ? 0.0f : vanilla * equipProgress.getFloat();
	}

	/**
	 * The swing animation's progress.
	 *
	 * <p>Clamped at 1 rather than allowed past it: the transforms downstream read this as a
	 * normalised 0..1 and feeding them 3 does not make a faster swing, it makes an arm that
	 * leaves the screen.
	 */
	public float swingProgress(float vanilla) {
		if (!isEnabled() || vanilla <= 0.0f) {
			return vanilla;
		}
		if (swingMode.is("None")) {
			return 0.0f;
		}
		return Mth.clamp(vanilla * swingSpeed.getFloat(), 0.0f, 1.0f);
	}

	/** Whether the eat/drink/brush hand jiggle still runs. */
	public boolean showsUseAnimation() {
		return !isEnabled() || useAnimation.is("Vanilla");
	}
}
