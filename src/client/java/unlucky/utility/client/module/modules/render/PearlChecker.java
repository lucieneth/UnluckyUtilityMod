package unlucky.utility.client.module.modules.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.ProjectilePathUtil.ProjectileType;
import unlucky.utility.client.util.Render3D;

/** Owner labels, throw/landing notices, and landing prediction for ender pearls. */
public class PearlChecker extends Module {
	public final BooleanSetting labels = add(new BooleanSetting("Owner labels",
			"Show the owner's name over active pearls", true));
	public final NumberSetting labelScale = add(new NumberSetting("Label scale",
			"Size of pearl owner labels", 0.8, 0.25, 3, 0.05), labels::get);
	public final ColorSetting labelColor = add(new ColorSetting("Label color",
			"Color of pearl owner labels", 0xFFFFFFFF), labels::get);
	public final BooleanSetting ignoreSelf = add(new BooleanSetting("Ignore self",
			"Hide your own pearl labels and notifications", true));
	public final BooleanSetting notifyThrow = add(new BooleanSetting("Notify throw",
			"Report newly spawned pearls in chat", true));
	public final BooleanSetting notifyLand = add(new BooleanSetting("Notify land",
			"Report where tracked pearls disappear", true));
	public final BooleanSetting prediction = add(new BooleanSetting("Predict landing",
			"Simulate active pearls and mark their first block collision", true));
	public final BooleanSetting notifyPrediction = add(new BooleanSetting("Notify prediction",
			"Report the first predicted landing for each pearl", false), prediction::get);
	public final NumberSetting markerSize = add(new NumberSetting("Marker size",
			"Half-size of the predicted landing box", 0.25, 0.05, 1, 0.05), prediction::get);
	public final ColorSetting markerColor = add(new ColorSetting("Marker color",
			"Color of predicted landing markers", 0xCC00C8FF), prediction::get);

	private record Tracked(String owner, Vec3 start, Vec3 last, boolean self) {
		Tracked at(Vec3 position) {
			return new Tracked(owner, start, position, self);
		}
	}

	private final Map<UUID, Tracked> tracked = new HashMap<>();
	private final Set<UUID> predicted = new HashSet<>();
	private final ProjectilePathUtil.ResultBuffer pathBuffer = new ProjectilePathUtil.ResultBuffer();
	private String dimension;

	public PearlChecker() {
		super("PearlChecker", "Labels pearls and predicts where they will land", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			clear();
			return;
		}
		String currentDimension = mc().level.dimension().identifier().toString();
		if (!currentDimension.equals(dimension)) {
			dimension = currentDimension;
			clear();
		}

		Set<UUID> seen = new HashSet<>();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof ThrownEnderpearl pearl)) {
				continue;
			}
			UUID id = pearl.getUUID();
			seen.add(id);
			Entity ownerEntity = pearl.getOwner();
			boolean self = ownerEntity == mc().player;
			String owner = ownerName(ownerEntity);
			Tracked old = tracked.get(id);
			if (old == null) {
				old = new Tracked(owner, pearl.position(), pearl.position(), self);
				tracked.put(id, old);
				if (notifyThrow.get() && !(ignoreSelf.get() && self) && owner != null) {
					ChatUtil.info("§f" + owner + " §7threw a pearl at §b"
							+ pearl.blockPosition().toShortString() + " §8(" + oneDecimal(mc().player.distanceTo(pearl)) + "m)");
				}
			} else {
				old = new Tracked(old.owner() == null ? owner : old.owner(), old.start(),
						pearl.position(), old.self() || self);
				tracked.put(id, old);
			}
			if (ignoreSelf.get() && self) {
				continue;
			}
			if (labels.get() && owner != null) {
				Render3D.blockLabel(owner, BlockPos.containing(pearl.position()).above(),
						labelColor.get(), labelScale.getFloat());
			}
			if (prediction.get()) {
				drawPrediction(pearl, old);
			}
		}

		for (Iterator<Map.Entry<UUID, Tracked>> it = tracked.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Tracked> entry = it.next();
			if (seen.contains(entry.getKey())) {
				continue;
			}
			Tracked pearl = entry.getValue();
			if (notifyLand.get() && pearl.owner() != null && !(ignoreSelf.get() && pearl.self())) {
				double distance = mc().player.position().distanceTo(pearl.last());
				double travelled = pearl.start().distanceTo(pearl.last());
				ChatUtil.info("§f" + pearl.owner() + "§7's pearl landed near §b"
						+ BlockPos.containing(pearl.last()).toShortString() + " §8(" + oneDecimal(distance)
						+ "m away, " + oneDecimal(travelled) + "m travelled)");
			}
			predicted.remove(entry.getKey());
			it.remove();
		}
	}

	private void drawPrediction(ThrownEnderpearl pearl, Tracked info) {
		ProjectilePathUtil.ResultBuffer path = ProjectilePathUtil.simulate(mc().level, pearl,
				pearl.position(), pearl.getDeltaMovement(), ProjectileType.ENDER_PEARL, 400,
				true, entity -> entity != pearl.getOwner(), pathBuffer);
		if (path.hit() == null) {
			return;
		}
		Vec3 end = path.end();
		double size = markerSize.get();
		int color = markerColor.get();
		Render3D.box(new AABB(end.x - size, end.y - size, end.z - size,
				end.x + size, end.y + size, end.z + size), color, 1.5f,
				ColorUtil.withAlpha(color, Math.max(25, ((color >>> 24) & 0xFF) / 4)), true);
		if (notifyPrediction.get() && predicted.add(pearl.getUUID()) && info.owner() != null) {
			ChatUtil.info("§f" + info.owner() + "§7's pearl is predicted at §b"
					+ BlockPos.containing(end).toShortString());
		}
	}

	private static String ownerName(Entity owner) {
		if (owner instanceof Player player) {
			return player.getGameProfile().name();
		}
		return owner == null ? null : owner.getName().getString();
	}

	private static String oneDecimal(double value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private void clear() {
		tracked.clear();
		predicted.clear();
	}

	@Override
	protected void onDisable() {
		clear();
	}
}
