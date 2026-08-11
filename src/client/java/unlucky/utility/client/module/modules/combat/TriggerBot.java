package unlucky.utility.client.module.modules.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.TargetingUtil;

/** Attacks automatically whenever your crosshair rests on a valid target. */
public class TriggerBot extends Module {
	// per-mob whitelists, opened by right-clicking the group toggles in the GUI
	public final unlucky.utility.client.settings.EntityListSetting hostileMobs =
			new unlucky.utility.client.settings.EntityListSetting("Hostile mobs", "Which hostile mobs to trigger on");
	public final unlucky.utility.client.settings.EntityListSetting passiveMobs =
			new unlucky.utility.client.settings.EntityListSetting("Passive mobs", "Which passive mobs to trigger on");

	public final BooleanSetting players = add(new BooleanSetting("Players", "Trigger on players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles", "Trigger on hostile mobs — right-click to pick which", true)
			.withMobList(hostileMobs, true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives", "Trigger on passive mobs — right-click to pick which", false)
			.withMobList(passiveMobs, false));
	public final ModeSetting speed = add(new ModeSetting("Speed", "Attributes = full weapon charge, CPS = flat rate", "Attributes", "Attributes", "CPS"));
	public final NumberSetting cps = add(new NumberSetting("CPS", "Clicks per second", 8, 1, 20, 1),
			() -> speed.is("CPS"));
	public final NumberSetting minAttackCharge = add(new NumberSetting("Min attack charge",
			"Required vanilla attack charge before an Attributes-mode hit", 1.0, 0.0, 1.0, 0.05),
			() -> speed.is("Attributes"));
	public final BooleanSetting holdToAttack = add(new BooleanSetting("Only while attack held",
			"Require the normal attack key to be held", false));
	public final ModeSetting onlyHolding = add(new ModeSetting("Only while holding", "Required held item",
			"Any", "Any", "Weapon", "Sword", "Axe", "Mace"));
	public final NumberSetting activationDelay = add(new NumberSetting("Activation delay",
			"Ticks a new crosshair target must remain valid before the first hit", 0, 0, 20, 1));
	public final NumberSetting delayVariance = add(new NumberSetting("Delay variance",
			"Extra randomized ticks added to activation delay", 0, 0, 10, 1),
			() -> activationDelay.getInt() > 0);
	public final NumberSetting crosshairGrace = add(new NumberSetting("Crosshair grace",
			"Keep a target for this many ticks through harmless raycast jitter", 0, 0, 5, 1));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUIs", "Don't attack with a screen open", true));
	public final BooleanSetting pauseWhileUsing = add(new BooleanSetting("Pause while using item",
			"Do not interrupt eating, blocking, or drawing another item", true));

	private int ticksSinceAttack;
	private int targetTicks;
	private int requiredTargetTicks;
	private int graceTicks;
	private LivingEntity currentTarget;

	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public TriggerBot() {
		super("TriggerBot", "Attacks what you aim at", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
		// config-persisted, GUI-hidden; edited through the right-click picker
		add(hostileMobs);
		add(passiveMobs);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return;
		}
		if (pauseWhileUsing.get() && mc().player.isUsingItem()) {
			resetTarget();
			return;
		}
		if (AutoEat.pauses(pauseOnEat)) {
			return;
		}
		if (holdToAttack.get() && !mc().options.keyAttack.isDown() || !holdingAllowed()) {
			resetTarget();
			return;
		}

		LivingEntity target = crosshairTarget();
		if (target == null) {
			if (graceTicks-- > 0 && currentTarget != null
					&& TargetingUtil.matches(mc().player, currentTarget, targetFilter())) {
				target = currentTarget;
			} else {
				resetTarget();
				return;
			}
		} else {
			graceTicks = crosshairGrace.getInt();
		}
		if (target != currentTarget) {
			currentTarget = target;
			targetTicks = 0;
			requiredTargetTicks = activationDelay.getInt()
					+ (delayVariance.getInt() == 0 ? 0 : mc().player.getRandom().nextInt(delayVariance.getInt() + 1));
			ticksSinceAttack = 0;
		}
		if (++targetTicks < requiredTargetTicks) {
			return;
		}
		ticksSinceAttack++;
		boolean ready = speed.is("Attributes")
				? mc().player.getAttackStrengthScale(0.0f) >= minAttackCharge.getFloat()
				: CombatUtil.ready(false, cps.get(), ticksSinceAttack);
		if (ready) {
			CombatUtil.attack(target);
			ticksSinceAttack = 0;
		}
	}

	@Override
	protected void onDisable() {
		resetTarget();
	}

	private LivingEntity crosshairTarget() {
		if (mc().hitResult instanceof EntityHitResult hit && mc().hitResult.getType() == HitResult.Type.ENTITY
				&& hit.getEntity() instanceof LivingEntity target
				&& TargetingUtil.matches(mc().player, target, targetFilter())) return target;
		return null;
	}

	private boolean holdingAllowed() {
		var held = mc().player.getMainHandItem();
		return switch (onlyHolding.get()) {
			case "Weapon" -> CombatItemUtil.isMeleeWeapon(held);
			case "Sword" -> CombatItemUtil.isSword(held);
			case "Axe" -> CombatItemUtil.isAxe(held);
			case "Mace" -> CombatItemUtil.isMace(held);
			default -> true;
		};
	}

	private void resetTarget() {
		currentTarget = null;
		targetTicks = 0;
		requiredTargetTicks = 0;
		graceTicks = 0;
		ticksSinceAttack = 0;
	}

	/** Uses the same friend-safe classification as Aura and TargetStrafe. */
	private TargetingUtil.Filter targetFilter() {
		return new TargetingUtil.Filter()
				.groups(players.get(), hostiles.get(), passives.get())
				.typeLists(hostileMobs, passiveMobs);
	}
}
