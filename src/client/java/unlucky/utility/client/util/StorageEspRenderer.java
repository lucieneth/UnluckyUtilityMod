package unlucky.utility.client.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Shader;

/**
 * Feeds storage shapes into the entity-outline mask, the same target mobs and
 * players already render into. Everything that makes the result look like ESP —
 * the uniform border and the silhouette fill — happens afterwards in the
 * {@code entity_outline} post chain, so this only has to lay down solid,
 * correctly-colored coverage.
 *
 * <p>Submitted before entities so that where a container and a mob overlap on
 * screen, the mob's color is the one that survives: the outline pipelines carry
 * no depth state at all (their {@code DepthStencilState} is null), so nothing in
 * the mask depth-tests and overlap is settled purely by draw order.
 */
public final class StorageEspRenderer {
	private StorageEspRenderer() {
	}

	/** Emits every active storage target into the outline mask. */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector) {
		Shader esp = UnluckyClient.INSTANCE.modules.get(Shader.class);
		if (!esp.isEnabled()) {
			return;
		}
		var targets = esp.storageTargets();
		if (targets.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 camera = mc.gameRenderer.mainCamera().position();
		RenderType type = RenderTypes.outline(ChamsRenderType.WHITE);

		for (Shader.Storage target : targets) {
			AABB box = target.box().move(-camera.x, -camera.y, -camera.z);
			int color = target.color();
			collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> emitBox(pose, consumer, box, color));
		}
	}

	/**
	 * Six outward-facing quads. Only the coverage matters — the post chain derives
	 * both the border and the fill from the mask's alpha — so the faces are flat
	 * color against the white texture with no lighting or normals.
	 */
	private static void emitBox(PoseStack.Pose pose, VertexConsumer consumer, AABB box, int argb) {
		float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
		float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

		// down / up
		quad(pose, consumer, argb, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
		quad(pose, consumer, argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
		// north / south
		quad(pose, consumer, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
		quad(pose, consumer, argb, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
		// west / east
		quad(pose, consumer, argb, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
		quad(pose, consumer, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer consumer, int argb,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		vertex(pose, consumer, argb, ax, ay, az);
		vertex(pose, consumer, argb, bx, by, bz);
		vertex(pose, consumer, argb, cx, cy, cz);
		vertex(pose, consumer, argb, dx, dy, dz);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, int argb, float x, float y, float z) {
		consumer.addVertex(pose, x, y, z).setColor(argb).setUv(0.0f, 0.0f);
	}
}
