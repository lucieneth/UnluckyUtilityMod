package unlucky.utility.client.util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.hud.HudModule;

/**
 * Session-scoped combat telemetry: totem pops per entity, plus approximate kills
 * and deaths. Kills are inherently a guess on the client (no server credit) — an
 * entity we recently hit that starts dying or vanishes is counted. Everything
 * resets when the connection changes (new server), surviving dimension hops.
 */
public final class SessionTracker {
	private static final long KILL_WINDOW_MS = 2000;
	private static final long ATTACK_EXPIRY_MS = 5000;

	private long sessionStart = System.currentTimeMillis();
	private int kills;
	private int deaths;
	private boolean wasDead;
	private Object lastConnection;

	private final Map<UUID, Integer> pops = new HashMap<>();
	private final Map<Integer, Long> attacked = new HashMap<>(); // entity id -> last hit ms
	private UUID lastTarget;

	/** Called for every attack the local player makes (all attack paths route through gameMode.attack). */
	public void onAttack(Entity target) {
		if (target instanceof LivingEntity living && target != Minecraft.getInstance().player) {
			attacked.put(target.getId(), System.currentTimeMillis());
			lastTarget = living.getUUID();
		}
	}

	/** Called when any living entity fires the totem-pop event. */
	public void onTotemPop(LivingEntity entity) {
		int count = pops.merge(entity.getUUID(), 1, Integer::sum);
		HudModule hud = UnluckyClient.INSTANCE.modules.get(HudModule.class);
		if (hud != null && hud.popCounter.get() && hud.popCounterAnnounce.get()) {
			UnluckyClient.INSTANCE.notifications.add("Totem",
					entity.getDisplayName().getString() + " popped (" + count + ")",
					new ItemStack(Items.TOTEM_OF_UNDYING));
		}
	}

	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != lastConnection) {
			reset();
			lastConnection = mc.getConnection();
		}
		if (mc.level == null) {
			return;
		}
		// deaths: alive -> dead transition of the local player
		boolean dead = mc.player != null && !mc.player.isAlive();
		if (dead && !wasDead) {
			deaths++;
		}
		wasDead = dead;

		// kills: an entity we recently hit that is now dying or gone
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, Long>> it = attacked.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Long> entry = it.next();
			Entity entity = mc.level.getEntity(entry.getKey());
			if (entity == null) {
				if (now - entry.getValue() < KILL_WINDOW_MS) {
					kills++;
				}
				it.remove();
			} else if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
				kills++;
				it.remove();
			} else if (now - entry.getValue() > ATTACK_EXPIRY_MS) {
				it.remove();
			}
		}
	}

	public void reset() {
		sessionStart = System.currentTimeMillis();
		kills = 0;
		deaths = 0;
		wasDead = false;
		pops.clear();
		attacked.clear();
		lastTarget = null;
	}

	public long sessionMs() {
		return System.currentTimeMillis() - sessionStart;
	}

	public int kills() {
		return kills;
	}

	public int deaths() {
		return deaths;
	}

	public int selfPops() {
		Player player = Minecraft.getInstance().player;
		return player == null ? 0 : pops.getOrDefault(player.getUUID(), 0);
	}

	public int targetPops() {
		return lastTarget == null ? 0 : pops.getOrDefault(lastTarget, 0);
	}

	public boolean hasTarget() {
		return lastTarget != null;
	}
}
