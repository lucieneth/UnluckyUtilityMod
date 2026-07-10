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
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180.0f + bodyAngle;
			living.yRot = bodyAngle + headOffset;
			living.xRot = pitch;
			living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
			living.boundingBoxHeight = living.boundingBoxHeight / living.scale;
			living.scale = 1.0f;
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
		// fit with breathing room: 0.82 leaves margin for hats/armor that poke
		// past the bounding box, and humanoids get a wider effective width so
		// swinging arms and held items stay inside the card
		float h = Math.max(state.boundingBoxHeight, 0.25f);
		float w = Math.max(Math.max(state.boundingBoxWidth, 0.25f), h * 0.55f);
		float scale = 0.82f * Math.min((y1 - y0) / h, (x1 - x0) / w);
		g.entity(state, scale, new Vector3f(0.0f, state.boundingBoxHeight / 2.0f, 0.0f), FLIP, null, x0, y0, x1, y1);
	}
}
