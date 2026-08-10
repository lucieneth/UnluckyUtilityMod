package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.FakePlayerEntity;

/** Client-only practice targets inserted into ClientLevel entity storage, never player info. */
public class FakePlayer extends Module {
	private static int nextEntityId = -100_000;

	public final StringSetting name = add(new StringSetting("Name", "Displayed practice-player name", "Unlucky", 16));
	public final NumberSetting health = add(new NumberSetting("Health", "Spawn health", 20, 1, 20, 1));
	public final NumberSetting absorption = add(new NumberSetting("Absorption", "Spawn absorption hearts", 0, 0, 20, 1));
	public final BooleanSetting copyInventory = add(new BooleanSetting("Copy inventory", "Copy main inventory stacks", true));
	public final BooleanSetting copyArmor = add(new BooleanSetting("Copy armor", "Copy equipped armor", true));
	public final BooleanSetting copyOffhand = add(new BooleanSetting("Copy offhand", "Copy the offhand stack", true));
	public final BooleanSetting copyEffects = add(new BooleanSetting("Copy effects", "Copy active status effects", false));
	public final BooleanSetting copyPose = add(new BooleanSetting("Copy pose", "Copy camera rotation and current pose", true));
	public final NumberSetting maxPlayers = add(new NumberSetting("Max fake players", "Hard client-side entity cap", 8, 1, 32, 1));
	public final ActionSetting spawnAction = add(new ActionSetting("Spawn at me", "Create a practice player here", this::spawn));
	public final ActionSetting removeNearestAction = add(new ActionSetting("Remove nearest", "Remove the nearest practice player", this::removeNearest));
	public final ActionSetting clearAction = add(new ActionSetting("Clear all", "Remove every practice player", this::clear));

	private final List<FakePlayerEntity> spawned = new ArrayList<>();
	private Object levelIdentity;

	public FakePlayer() {
		super("FakePlayer", "Spawns client-only practice targets", Category.PLAYER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		spawn();
	}

	@Override
	public void onTick() {
		if (mc().level != levelIdentity) {
			spawned.clear();
			levelIdentity = mc().level;
		}
		spawned.removeIf(Entity::isRemoved);
	}

	public FakePlayerEntity spawn() {
		if (!isEnabled() || mc().level == null || mc().player == null
				|| spawned.size() >= maxPlayers.getInt()) return null;
		String chosen = name.get().isBlank() ? "Unlucky" : name.get().trim();
		FakePlayerEntity fake = new FakePlayerEntity(mc().level,
				new GameProfile(UUID.randomUUID(), chosen));
		fake.setId(nextEntityId--);
		fake.setPos(mc().player.position());
		fake.setYRot(mc().player.getYRot());
		fake.setXRot(mc().player.getXRot());
		fake.yHeadRot = mc().player.yHeadRot;
		fake.yBodyRot = mc().player.yBodyRot;
		fake.setHealth(health.getFloat());
		fake.setAbsorptionAmount(absorption.getFloat());
		if (copyPose.get()) fake.setPose(mc().player.getPose());
		if (copyInventory.get()) {
			int size = Math.min(fake.getInventory().getContainerSize(), mc().player.getInventory().getContainerSize());
			for (int i = 0; i < size; i++) {
				fake.getInventory().setItem(i, mc().player.getInventory().getItem(i).copy());
			}
		}
		if (copyArmor.get()) {
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
					EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				fake.setItemSlot(slot, mc().player.getItemBySlot(slot).copy());
			}
		}
		if (copyOffhand.get()) {
			fake.setItemInHand(InteractionHand.OFF_HAND, mc().player.getOffhandItem().copy());
		}
		if (copyEffects.get()) {
			for (MobEffectInstance effect : mc().player.getActiveEffects()) {
				fake.forceAddEffect(new MobEffectInstance(effect), null);
			}
		}
		mc().level.addEntity(fake);
		spawned.add(fake);
		levelIdentity = mc().level;
		return fake;
	}

	private void removeNearest() {
		if (mc().player == null) return;
		FakePlayerEntity nearest = null;
		double distance = Double.POSITIVE_INFINITY;
		for (FakePlayerEntity fake : spawned) {
			double d = fake.distanceToSqr(mc().player);
			if (!fake.isRemoved() && d < distance) { nearest = fake; distance = d; }
		}
		remove(nearest);
	}

	private void remove(FakePlayerEntity fake) {
		if (fake == null) return;
		if (fake.level() == mc().level) mc().level.removeEntity(fake.getId(), Entity.RemovalReason.DISCARDED);
		spawned.remove(fake);
	}

	public void clear() {
		for (FakePlayerEntity fake : List.copyOf(spawned)) remove(fake);
		spawned.clear();
	}

	@Override
	protected void onDisable() {
		clear();
	}
}
