package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.movement.AutoWalk;
import unlucky.utility.client.module.modules.player.AntiAFK;
import unlucky.utility.client.module.modules.player.AutoCraft;
import unlucky.utility.client.module.modules.world.AutoSmelt;
import unlucky.utility.client.util.InputActionCoordinator;
import unlucky.utility.client.util.InventoryActionCoordinator;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;
import static unlucky.utility.test.module.ModuleTestSupport.DWELL;

/**
 * No key or inventory lease held by an automation module survives its disable.
 */
public final class AutomationTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(AntiAFK.class, AutoCraft.class, AutoSmelt.class, AutoWalk.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyAutomationContracts(context);
	}

	/**
	 * The bounded-automation modules, checked for the thing that actually hurts.
	 *
	 * <p>These four are the only modules in the client that <em>hold</em> something across ticks
	 * on their own initiative — a movement key, an inventory lease. A leak there does not throw and
	 * does not render wrong: it walks you off a cliff after you switched the module off, or parks
	 * the inventory so nothing else can ever click again. Neither is visible in a sweep that only
	 * asks whether enabling something crashed.
	 *
	 * <p>Run as a real enable-tick-disable cycle rather than a predicate check, because the claim
	 * is about what survives a disable and only ticking can produce a hold to survive it.
	 */
	private void verifyAutomationContracts(ClientGameTestContext context) {
		List<Module> holders = context.computeOnClient(mc -> List.of(
				UnluckyClient.INSTANCE.modules.get(AntiAFK.class),
				UnluckyClient.INSTANCE.modules.get(AutoWalk.class),
				UnluckyClient.INSTANCE.modules.get(AutoCraft.class),
				UnluckyClient.INSTANCE.modules.get(AutoSmelt.class)));

		Map<Module, Boolean> before = new LinkedHashMap<>();
		context.runOnClient(mc -> {
			for (Module module : holders) {
				before.put(module, module.isEnabled());
				module.setEnabled(true);
			}
		});
		context.waitTicks(DWELL);
		context.runOnClient(mc -> {
			for (Module module : holders) {
				module.setEnabled(false);
			}
		});
		context.waitTicks(2);

		List<String> failures = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();
			for (InputActionCoordinator.Key key : InputActionCoordinator.Key.values()) {
				if (InputActionCoordinator.isHeld(key)) {
					problems.add("a synthetic " + key.name().toLowerCase()
							+ " key survived every module being disabled");
				}
			}
			Object owner = InventoryActionCoordinator.owner();
			for (Module module : holders) {
				if (owner == module) {
					problems.add(module.getName() + " still owns the inventory after disable");
				}
			}
			// AutoCraft and AutoSmelt with nothing open must be idle, not merely quiet: a module
			// reporting "working" against a menu that is not there is one that has kept a plan.
			AutoCraft craft = UnluckyClient.INSTANCE.modules.get(AutoCraft.class);
			AutoSmelt smelt = UnluckyClient.INSTANCE.modules.get(AutoSmelt.class);
			if (craft.status().contains("working") || smelt.status().contains("working")) {
				problems.add("a recipe module reports working with no supported menu open");
			}
			AntiAFK antiAfk = UnluckyClient.INSTANCE.modules.get(AntiAFK.class);
			if (antiAfk.acting()) {
				problems.add("a disabled AntiAFK still reports itself acting");
			}
			return problems;
		});

		context.runOnClient(mc -> before.forEach(Module::setEnabled));
		context.waitTick();

		if (!failures.isEmpty()) {
			throw new AssertionError("Bounded-automation contracts failed: "
					+ String.join("; ", failures));
		}
		LOGGER.info("[automation] no key or inventory lease survives a disable");
	}
}
