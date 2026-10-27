package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a living entity into a GUI box — the same submit path as the
 * inventory player preview, shared by the mob picker, TargetHUD and
 * PlayerModel. Extraction happens per call; only invoke for visible boxes.
 */
public final class HudEntity {
	private static final Quaternionf FLIP = new Quaternionf().rotateZ((float) Math.PI);

	private HudEntity() {
	}

	/**
	 * How a posed model sits in model space. The standing size sets the scale, so a glider is
	 * drawn as big as a standing player; the posed size is what has to fit in the box.
	 *
	 * @param centerY model-space height of the posed body's middle, negative when it dives head down
	 */
	public record Fit(float standingWidth, float standingHeight, float width, float height, float centerY) {
		/** A box fit for an entity that is not posed: its render state's bounding box, standing. */
		public static Fit of(EntityRenderState state) {
			float h = Math.max(state.boundingBoxHeight, 0.25f);
			float w = Math.max(state.boundingBoxWidth, 0.25f);
			return new Fit(w, h, Math.max(w, h * 0.55f), h, h / 2.0f);
		}

		/** Wider than tall: a glider, swimmer or crawler, which wants a landscape box. */
		public boolean lying() {
			return width > height;
		}

		/**
		 * The scale a standing model gets in a {@code standW} x {@code standH} box, shrunk only
		 * when the posed body would not fit the {@code boxW} x {@code boxH} box it is drawn in.
		 * 0.82 leaves margin for hats and armor past the bounding box, and humanoids get a wider
		 * effective width so swinging arms and held items stay inside the card.
		 */
		public float scale(float standW, float standH, float boxW, float boxH) {
			float stand = 0.82f * Math.min(standH / standingHeight, standW / Math.max(standingWidth, standingHeight * 0.55f));
			return Math.min(stand, 0.9f * Math.min(boxW / width, boxH / height));
		}
	}

	/**
	 * Poses a model the way the inventory preview does: a glider or swimmer tips over as far
	 * as vanilla tips it, flat with the elytra spread when looking ahead. Vanilla rotates the
	 * body about its feet, and the GUI projection is orthographic, so the posed body spans
	 * {@code height * cos(lean)} up from the feet, plus its own thickness. Call after
	 * {@code scale} is reset to 1 and {@code xRot} is set.
	 */
	public static Fit frame(LivingEntityRenderState living, LivingEntity entity) {
		var standing = entity.getDimensions(net.minecraft.world.entity.Pose.STANDING);
		float entityScale = Math.max(entity.getScale(), 0.01f);
		float height = Math.max(standing.height() / entityScale, 0.25f);
		float width = Math.max(standing.width() / entityScale, 0.25f);
		living.boundingBoxWidth = width;
		living.isAutoSpinAttack = false; // riptide's spin is a blur in a card
		if (living.pose == net.minecraft.world.entity.Pose.SPIN_ATTACK
				|| living.pose == net.minecraft.world.entity.Pose.SLEEPING) {
			// a sleeper lies sideways across the card; show them standing instead
			living.pose = net.minecraft.world.entity.Pose.STANDING;
			living.bedOrientation = null;
		}
		float lean = 0.0f;
		float posedWidth = Math.max(width, height * 0.55f);
		if (living instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
			// vanilla tips a glider (and a swimmer in water) by 90 plus the head's pitch
			float full = 90.0f + living.xRot;
			if (humanoid.isFallFlying && living instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar) {
				lean = avatar.fallFlyingScale() * full;
				avatar.shouldApplyFlyingYRot = false; // the turn roll swings the body sideways out of frame
				posedWidth = Math.max(posedWidth, 1.6f); // spread wings
			} else if (humanoid.swimAmount > 0.0f) {
				lean = humanoid.swimAmount * (living.isInWater ? full : 90.0f);
				humanoid.isVisuallySwimming = false; // its extra drop would push the body out of the box
			}
		}
		double radians = Math.toRadians(lean);
		float tip = height * (float) Math.cos(radians);
		float posedHeight = Math.max(Math.abs(tip) + 0.8f * Math.abs((float) Math.sin(radians)), 0.25f);
		living.boundingBoxHeight = posedHeight;
		return new Fit(width, height, posedWidth, posedHeight, tip / 2.0f);
	}

	/**
	 * @param bodyAngle  body yaw in degrees, 0 = facing the camera
	 * @param headOffset head yaw relative to the body
	 * @param pitch      head pitch, positive = looking down
	 */
	public static void draw(GuiGraphicsExtractor g, LivingEntity entity, int x0, int y0, int x1, int y1,
			float bodyAngle, float headOffset, float pitch) {
		draw(g, entity, x0, y0, x1, y1, bodyAngle, headOffset, pitch, true, true);
	}

	public static void draw(GuiGraphicsExtractor g, LivingEntity entity, int x0, int y0, int x1, int y1,
			float bodyAngle, float headOffset, float pitch, boolean armor, boolean heldItems) {
		EntityRenderer<? super LivingEntity, ?> renderer =
				Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		EntityRenderState state = renderer.createRenderState(entity, 1.0f);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		Fit fit = Fit.of(state);
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180.0f + bodyAngle;
			living.yRot = bodyAngle + headOffset;
			living.xRot = pitch;
			living.scale = 1.0f;
			fit = frame(living, entity);
		}
		// strip gear state-side only — the real entity is never touched
		if (!armor && state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
			humanoid.headEquipment = net.minecraft.world.item.ItemStack.EMPTY;
			humanoid.chestEquipment = net.minecraft.world.item.ItemStack.EMPTY;
			humanoid.legsEquipment = net.minecraft.world.item.ItemStack.EMPTY;
			humanoid.feetEquipment = net.minecraft.world.item.ItemStack.EMPTY;
		}
		if (!heldItems && state instanceof net.minecraft.client.renderer.entity.state.ArmedEntityRenderState armed) {
			armed.rightHandItemState.clear();
			armed.rightHandItemStack = net.minecraft.world.item.ItemStack.EMPTY;
			armed.leftHandItemState.clear();
			armed.leftHandItemStack = net.minecraft.world.item.ItemStack.EMPTY;
		}
		float scale = fit.scale(x1 - x0, y1 - y0, x1 - x0, y1 - y0);
		g.entity(state, scale, new Vector3f(0.0f, fit.centerY(), 0.0f), FLIP, null, x0, y0, x1, y1);
	}
}
