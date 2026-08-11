package unlucky.utility.client.module.modules.combat;

import java.util.Set;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.Render3D;

/** Modest continuous reach applied through vanilla's own interaction-range getters. */
public class Reach extends Module {
	private static final Set<String> DEFAULT_WEAPONS = Set.of(
			"minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
			"minecraft:golden_sword", "minecraft:diamond_sword", "minecraft:netherite_sword",
			"minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe",
			"minecraft:golden_axe", "minecraft:diamond_axe", "minecraft:netherite_axe",
			"minecraft:mace");

	public final NumberSetting entityReach = add(new NumberSetting("Entity reach",
			"Distance used by normal entity targeting", 3.5, 3.0, 6.0, 0.1));
	public final NumberSetting blockReach = add(new NumberSetting("Block reach",
			"Distance used by normal block targeting", 4.5, 3.0, 6.0, 0.1));
	public final BooleanSetting separateValues = add(new BooleanSetting("Separate values",
			"Use independent entity and block distances", true));
	public final BooleanSetting onlyWeapon = add(new BooleanSetting("Only weapon for entity reach",
			"Extend entity targeting only with a sword, axe or mace", false));
	public final ItemListSetting weapons = add(new ItemListSetting("Weapons",
			"Weapons that can activate the entity-reach gate", CombatItemUtil::isMeleeWeapon,
			DEFAULT_WEAPONS), onlyWeapon::get);
	public final BooleanSetting respectCreative = add(new BooleanSetting(
			"Respect creative reach minimum",
			"Never shorten a longer reach already supplied by the current game mode", true));
	public final BooleanSetting renderReach = add(new BooleanSetting("Render effective reach",
			"Draw the current block and entity ray lengths", false));

	public Reach() {
		super("Reach", "Extends normal block and entity targeting without packet stepping",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	public double blockRange(double vanilla) {
		return choose(vanilla, blockReach.get());
	}

	public double entityRange(double vanilla) {
		if (!isEnabled() || mc().player == null
				|| (onlyWeapon.get() && !weapons.contains(mc().player.getMainHandItem().getItem()))) {
			return vanilla;
		}
		double configured = separateValues.get() ? entityReach.get() : blockReach.get();
		return respectCreative.get() ? Math.max(vanilla, configured) : configured;
	}

	private double choose(double vanilla, double configured) {
		if (!isEnabled()) return vanilla;
		return respectCreative.get() ? Math.max(vanilla, configured) : configured;
	}

	@Override
	public void onTick() {
		if (!renderReach.get() || mc().player == null) return;
		Vec3 eye = mc().player.getEyePosition();
		Vec3 look = mc().player.getLookAngle();
		// Ask the getters the pick path asks, so the overlay also tells the truth when
		// InfiniteInteract owns them instead of Reach.
		Render3D.line(eye, eye.add(look.scale(mc().player.blockInteractionRange())),
				0xB040A0FF, 1.5f, true);
		Render3D.line(eye, eye.add(look.scale(mc().player.entityInteractionRange())),
				0xB0FF5050, 1.5f, true);
	}
}
