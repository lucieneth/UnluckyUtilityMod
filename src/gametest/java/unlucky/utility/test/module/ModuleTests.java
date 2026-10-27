package unlucky.utility.test.module;

import java.util.List;

/** Every in-world module test, in the order they run. */
public final class ModuleTests {
	/**
	 * Order matters: the earlier tests read the fresh scene, and HeldAttacks kills its mobs
	 * and flips the game mode, so anything that needs them untouched goes before it.
	 */
	public static final List<ModuleTest> ALL = List.of(
			new TargetingTest(),
			new MovementTest(),
			new VisualsTest(),
			new RainbowEnchantTest(),
			new AutomationTest(),
			new HeldAttacksTest(),
			new InfiniteInteractTest(),
			new ElytraMaceTest());

	private ModuleTests() {
	}
}
