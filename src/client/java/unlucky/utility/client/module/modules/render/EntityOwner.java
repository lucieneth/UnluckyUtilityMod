package unlucky.utility.client.module.modules.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.MinecraftServicesApi;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Puts a name over the things people leave behind them: tamed animals, and the
 * pearl that just flew past. On an anarchy server the wolves at a base tell you
 * who lives there, which is the whole reason this is worth drawing.
 *
 * <p>An owner arrives as a bare UUID. If that player is loaded we read the name
 * straight off them; if not, the session server is asked once and the answer is
 * cached for the session, because the same base is usually the same four names.
 *
 * <p>Friends are coloured by {@link FriendManager} like everywhere else — a
 * name is far more useful when you can tell at a glance whose it is.
 *
 * <p>Reference: Meteor's EntityOwner.
 */
public class EntityOwner extends Module {
	/** Placeholder while the profile request is in flight. */
	private static final String PENDING = "…";
	private static final String UNKNOWN = "?";

	public final BooleanSetting tamed = add(new BooleanSetting("Tamed animals",
			"Name the owner of wolves, cats, parrots and horses", true));
	public final BooleanSetting projectiles = add(new BooleanSetting("Projectiles",
			"Name the thrower of pearls, arrows and other projectiles", true));
	public final NumberSetting range = add(new NumberSetting("Range",
			"How far away an entity can be and still be labelled", 64, 8, 256, 8));
	public final NumberSetting scale = add(new NumberSetting("Scale",
			"Size of the label", 1.0, 0.25, 3.0, 0.05));
	public final BooleanSetting constantSize = add(new BooleanSetting("Constant size",
			"Keep the label the same size regardless of distance", false));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Colour of the owner name", 0xFFD8DEE6));
	/** Same colour NameTags uses for friends, so one glance means the same thing everywhere. */
	public final ColorSetting friendColor = add(new ColorSetting("Friend color",
			"Colour used when the owner is a friend", 0xFF55DDFF));

	/** Session cache of UUID → name, including the in-flight placeholder. */
	private final Map<UUID, String> names = new HashMap<>();

	public EntityOwner() {
		super("EntityOwner", "Shows who owns pets and projectiles", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onDisable() {
		names.clear();
	}

	/** Called from the HUD layer every frame — including while off, so gate here. */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!isEnabled() || mc().level == null || mc().player == null) {
			return;
		}
		double maxDistance = range.getInt();
		int guiWidth = g.guiWidth();
		int guiHeight = g.guiHeight();

		for (Entity entity : mc().level.entitiesForRendering()) {
			UUID owner = ownerOf(entity);
			if (owner == null || entity.distanceToSqr(mc().player) > maxDistance * maxDistance) {
				continue;
			}
			Vec3 head = entity.getPosition(partialTick)
					.add(0, entity.getBbHeight() + 0.4, 0);
			Vec3 screen = Render3D.worldToScreen(head, guiWidth, guiHeight);
			if (screen == null) {
				continue;
			}

			String name = nameOf(owner);
			float distance = entity.distanceTo(mc().player);
			float distanceScale = constantSize.get()
					? 1.0f : Mth.clamp(12.0f / Math.max(distance, 1.0f), 0.35f, 1.5f);
			float s = scale.getFloat() * distanceScale;

			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate((int) Math.round(screen.x), (int) Math.round(screen.y));
			pose.scale(s, s);
			int width = Render2D.width(name);
			int top = -Render2D.FONT_HEIGHT;
			int x = -Math.round(width / 2.0f);
			Render2D.rect(g, x - 1, top - 1, width + 2, Render2D.FONT_HEIGHT + 1, 0x80000000);
			Render2D.text(g, name, x, top,
					FriendManager.isFriend(owner) ? friendColor.get() : color.get());
			pose.popMatrix();
		}
	}

	/**
	 * The owner of an entity, or null when it has none worth showing.
	 *
	 * <p>Projectiles are asked by their {@code getOwner}, which is only populated
	 * for entities the client can see; tamables carry a reference that survives
	 * the owner logging off, which is the case this module exists for.
	 */
	private UUID ownerOf(Entity entity) {
		if (entity instanceof TamableAnimal animal) {
			if (!tamed.get()) {
				return null;
			}
			EntityReference<LivingEntity> reference = animal.getOwnerReference();
			return reference == null ? null : reference.getUUID();
		}
		if (entity instanceof Projectile projectile) {
			if (!projectiles.get()) {
				return null;
			}
			Entity thrower = projectile.getOwner();
			return thrower instanceof Player player ? player.getUUID() : null;
		}
		return null;
	}

	/** Local players first (free and exact), then a cached session-server lookup. */
	private String nameOf(UUID uuid) {
		Player online = mc().level.getPlayerByUUID(uuid);
		if (online != null) {
			return online.getName().getString();
		}
		String cached = names.get(uuid);
		if (cached != null) {
			return cached;
		}
		names.put(uuid, PENDING);
		MinecraftServicesApi.fetchNameOf(uuid,
				name -> Minecraft.getInstance().execute(() -> names.put(uuid, name)),
				error -> Minecraft.getInstance().execute(() -> names.put(uuid, UNKNOWN)));
		return PENDING;
	}
}
