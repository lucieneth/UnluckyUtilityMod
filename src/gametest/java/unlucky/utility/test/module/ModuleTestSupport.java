package unlucky.utility.test.module;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the module tests share: the log, the dwell, and the summon-and-hit helpers. */
public final class ModuleTestSupport {
	public static final Logger LOGGER = LoggerFactory.getLogger("unlucky-test");

	/** Ticks a module stays on in the isolated pass. Frames render between ticks — the frames are the test. */
	public static final int DWELL = 4;

	private ModuleTestSupport() {
	}

	/** How far the server's idea of the player is from where the client stands. */
	public static float serverDrift(ClientGameTestContext context, TestServerContext server) {
		Vec3 client = context.computeOnClient(mc -> mc.player.position());
		Vec3 onServer = server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).position());
		return (float) client.distanceTo(onServer);
	}

	/** A still, silent zombie two blocks in front, found again by tag on the server. */
	public static int summonHeldTarget(ClientGameTestContext context, TestServerContext server, String tag) {
		return summonAt(context, server, tag, "~ ~ ~2");
	}

	public static int summonAt(ClientGameTestContext context, TestServerContext server, String tag, String where) {
		return summonAt(context, server, tag, where, "");
	}

	/** As above, with {@code extra} NBT appended — equipment, attributes, health. */
	public static int summonAt(ClientGameTestContext context, TestServerContext server, String tag, String where,
			String extra) {
		server.runCommand("execute at @p run summon minecraft:zombie " + where + " {NoAI:1b,Silent:1b,"
				+ "PersistenceRequired:1b,Tags:[\"" + tag + "\"]" + extra + "}");
		context.waitTicks(2);
		int id = server.computeOnServer(s -> {
			for (Entity entity : s.overworld().getAllEntities()) {
				if (entity.entityTags().contains(tag)) {
					return entity.getId();
				}
			}
			return -1;
		});
		if (id < 0) {
			throw new AssertionError("could not summon the " + tag + " zombie");
		}
		return id;
	}

	public static void attack(ClientGameTestContext context, int id) {
		context.runOnClient(mc -> mc.gameMode.attack(mc.player, mc.level.getEntity(id)));
	}

	/** Health on the server, 0 once it is dead or gone. */
	public static float serverHealth(TestServerContext server, int id) {
		return server.computeOnServer(s -> s.overworld().getEntity(id) instanceof LivingEntity living
				&& living.isAlive() ? living.getHealth() : 0.0f);
	}

	public static int damageTaken(TestServerContext server) {
		return server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getStats()
				.getValue(Stats.CUSTOM.get(Stats.DAMAGE_TAKEN)));
	}

	public static ItemStack stackOf(String id) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
	}
}
