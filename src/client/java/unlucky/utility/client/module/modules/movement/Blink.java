package unlucky.utility.client.module.modules.movement;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.FakePlayerEntity;
import unlucky.utility.client.util.PacketQueueManager;
import unlucky.utility.client.util.Render3D;

/** PacketQueueManager-backed Blink with discard-only panic semantics. */
public class Blink extends Module {
	private static int nextDummyId = -200_000;
	public final ModeSetting queueMode = add(new ModeSetting("Queue", "Packets held by Blink", "Movement only", "Movement only", "Movement + actions"));
	public final BooleanSetting dummyPlayer = add(new BooleanSetting("Dummy player", "Show a client-only copy at server position", true));
	public final NumberSetting maxTicks = add(new NumberSetting("Max ticks", "Hard queue lifetime", 100, 1, 1200, 1));
	public final NumberSetting maxPackets = add(new NumberSetting("Max queued packets", "Hard queue size", 300, 1, 2000, 10));
	public final ModeSetting onLimit = add(new ModeSetting("On limit", "Resolve a full/expired queue", "Flush", "Flush", "Cancel"));
	public final ModeSetting onDisable = add(new ModeSetting("On normal disable", "Resolve packets when toggled off", "Flush", "Flush", "Cancel"));
	public final BooleanSetting correctionDisable = add(new BooleanSetting("Auto disable on correction", "Discard after a server correction", true));
	public final BooleanSetting renderLine = add(new BooleanSetting("Render server position line", "Draw from server position to client position", true));
	public final BooleanSetting showCount = add(new BooleanSetting("Show queued packet count", "Label the server position with queue size", true));
	private FakePlayerEntity dummy;
	private boolean resolving;

	public Blink() {
		super("Blink", "Buffers explicitly allowed gameplay packets behind one safe owner",
				Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		if (mc().player == null || mc().level == null || mc().getConnection() == null) {
			PacketQueueManager.discard(this);
			clearDummy();
			return;
		}
		PacketQueueManager.QueueMode mode = queueMode.is("Movement + actions")
				? PacketQueueManager.QueueMode.MOVEMENT_AND_ACTIONS
				: PacketQueueManager.QueueMode.MOVEMENT_ONLY;
		if (!PacketQueueManager.begin(this, mode, maxTicks.getInt(), maxPackets.getInt(),
				this::limitReached, this::corrected)) return;
		if (dummyPlayer.get() && dummy == null) createDummy();
		if (!dummyPlayer.get()) clearDummy();
		Vec3 server = PacketQueueManager.serverPosition();
		if (server == null) return;
		if (renderLine.get()) Render3D.line(server.add(0, 0.9, 0), mc().player.position().add(0, 0.9, 0), 0xC08080FF, 1.5f, true);
		if (showCount.get()) Render3D.blockLabel("Blink " + PacketQueueManager.queuedCount(),
				new net.minecraft.core.BlockPos((int) Math.floor(server.x), (int) Math.floor(server.y + 2), (int) Math.floor(server.z)), 0xFFAAAAFF, 1.0f);
	}

	private void createDummy() {
		Vec3 server = PacketQueueManager.serverPosition();
		if (server == null || mc().player == null) return;
		dummy = new FakePlayerEntity(mc().level, new GameProfile(UUID.randomUUID(), mc().player.getName().getString()));
		dummy.setId(nextDummyId--);
		dummy.setPos(server);
		dummy.setYRot(mc().player.getYRot());
		dummy.setXRot(mc().player.getXRot());
		dummy.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, mc().player.getMainHandItem().copy());
		for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
				net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			dummy.setItemSlot(slot, mc().player.getItemBySlot(slot).copy());
		}
		mc().level.addEntity(dummy);
	}

	private void clearDummy() {
		if (dummy != null && mc().level != null && dummy.level() == mc().level && !dummy.isRemoved()) {
			mc().level.removeEntity(dummy.getId(), Entity.RemovalReason.DISCARDED);
		}
		dummy = null;
	}

	private void limitReached() { resolve(onLimit.is("Flush")); }
	private void corrected() {
		PacketQueueManager.discard(this);
		clearDummy();
		if (correctionDisable.get()) disableResolved();
	}

	private void resolve(boolean flush) {
		Vec3 server = PacketQueueManager.serverPosition();
		if (flush) PacketQueueManager.flush(this);
		else { PacketQueueManager.discard(this); snap(server); }
		clearDummy();
		disableResolved();
	}

	private void disableResolved() {
		resolving = true;
		try { if (isEnabled()) setEnabled(false); }
		finally { resolving = false; }
	}

	private void snap(Vec3 server) {
		if (server != null && mc().player != null) {
			mc().player.setPos(server);
			mc().player.setDeltaMovement(Vec3.ZERO);
		}
	}

	@Override protected void onDisable() {
		if (!resolving && PacketQueueManager.owns(this)) resolve(onDisable.is("Flush"));
		else clearDummy();
	}

	@Override protected void onPanic() {
		Vec3 server = PacketQueueManager.serverPosition();
		PacketQueueManager.discard(this);
		snap(server);
		clearDummy();
	}
}
