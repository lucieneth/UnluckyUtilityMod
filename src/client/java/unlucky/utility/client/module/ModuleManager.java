package unlucky.utility.client.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.module.modules.combat.AutoClicker;
import unlucky.utility.client.module.modules.combat.Aura;
import unlucky.utility.client.module.modules.combat.TriggerBot;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.module.modules.misc.AdBlocker;
import unlucky.utility.client.module.modules.misc.AntiToS;
import unlucky.utility.client.module.modules.misc.BookTools;
import unlucky.utility.client.module.modules.misc.SoundLocator;
import unlucky.utility.client.module.modules.misc.Spinbot;
import unlucky.utility.client.module.modules.movement.AFKVanillaFly;
import unlucky.utility.client.module.modules.movement.AutoSprint;
import unlucky.utility.client.module.modules.combat.TargetStrafe;
import unlucky.utility.client.module.modules.movement.BunnyHop;
import unlucky.utility.client.module.modules.movement.FakeFly;
import unlucky.utility.client.module.modules.movement.CreativeFlight;
import unlucky.utility.client.module.modules.movement.ElytraFly;
import unlucky.utility.client.module.modules.movement.Jetpack;
import unlucky.utility.client.module.modules.movement.NoJumpDelay;
import unlucky.utility.client.module.modules.movement.Speed;
import unlucky.utility.client.module.modules.movement.Velocity;
import unlucky.utility.client.module.modules.movement.RoadTrip;
import unlucky.utility.client.module.modules.movement.RocketJump;
import unlucky.utility.client.module.modules.movement.RocketMan;
import unlucky.utility.client.module.modules.movement.Updraft;
import unlucky.utility.client.module.modules.player.AutoExtinguish;
import unlucky.utility.client.module.modules.player.Capes;
import unlucky.utility.client.module.modules.player.AutoXPRepair;
import unlucky.utility.client.module.modules.player.Honker;
import unlucky.utility.client.module.modules.player.PagePirate;
import unlucky.utility.client.module.modules.render.AutoDrawDistance;
import unlucky.utility.client.module.modules.render.Chams;
import unlucky.utility.client.module.modules.render.ElytraPhysics;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.module.modules.render.MobESP;
import unlucky.utility.client.module.modules.render.NoFog;
import unlucky.utility.client.module.modules.render.PlayerESP;
import unlucky.utility.client.module.modules.render.StorageESP;
import unlucky.utility.client.module.modules.render.XRay;
import unlucky.utility.client.module.modules.visuals.Fullbright;
import unlucky.utility.client.module.modules.visuals.NoHurtCam;
import unlucky.utility.client.module.modules.visuals.Zoom;
import unlucky.utility.client.module.modules.world.Archaeology;
import unlucky.utility.client.module.modules.world.AutoDoors;
import unlucky.utility.client.module.modules.world.AutoFarm;
import unlucky.utility.client.module.modules.world.AutoWither;
import unlucky.utility.client.module.modules.world.BannerData;
import unlucky.utility.client.module.modules.world.BlockAirPlace;
import unlucky.utility.client.module.modules.world.ObsidianFarm;
import unlucky.utility.client.module.modules.world.TreasureESP;
import unlucky.utility.client.module.modules.world.VanityESP;
import unlucky.utility.client.module.modules.world.ChatSigns;
import unlucky.utility.client.module.modules.world.WaxAura;

public final class ModuleManager {
	private final List<Module> modules = new ArrayList<>();

	public void init() {
		register(new Fullbright());
		register(new Zoom());
		register(new NoHurtCam());
		register(new HudModule());
		register(new ThemeModule());
		register(new AutoDrawDistance());
		register(new AdBlocker());
		register(new ChatSigns());
		register(new AutoDoors());
		register(new WaxAura());
		register(new Honker());
		register(new ElytraFly());
		register(new RocketMan());
		register(new RocketJump());
		register(new Updraft());
		register(new RoadTrip());
		register(new AutoSprint());
		register(new AntiToS());
		register(new BookTools());
		register(new PagePirate());
		register(new BannerData());
		register(new PlayerESP());
		register(new MobESP());
		register(new Chams());
		register(new ElytraPhysics());
		register(new Freecam());
		register(new CreativeFlight());
		register(new Jetpack());
		register(new Speed());
		register(new BunnyHop());
		register(new TargetStrafe());
		register(new NoJumpDelay());
		register(new Velocity());
		register(new VanityESP());
		register(new SoundLocator());
		register(new Spinbot());
		register(new AutoExtinguish());
		register(new Capes());
		register(new FakeFly());
		register(new AutoFarm());
		register(new AutoXPRepair());
		register(new ObsidianFarm());
		register(new AFKVanillaFly());
		register(new BlockAirPlace());
		register(new AutoWither());
		register(new NoFog());
		register(new StorageESP());
		register(new TreasureESP());
		register(new Archaeology());
		register(new Aura());
		register(new TriggerBot());
		register(new AutoClicker());
		register(new XRay());
		modules.sort(Comparator.comparing(Module::getName));
	}

	private void register(Module module) {
		modules.add(module);
	}

	public List<Module> all() {
		return modules;
	}

	public List<Module> byCategory(Category category) {
		List<Module> result = new ArrayList<>();
		for (Module module : modules) {
			if (module.getCategory() == category) {
				result.add(module);
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	public <T extends Module> T get(Class<T> type) {
		for (Module module : modules) {
			if (type.isInstance(module)) {
				return (T) module;
			}
		}
		throw new IllegalStateException("Module not registered: " + type.getSimpleName());
	}

	public Module byName(String name) {
		for (Module module : modules) {
			if (module.getName().equals(name)) {
				return module;
			}
		}
		return null;
	}

	public void tick() {
		for (Module module : modules) {
			if (module.isEnabled()) {
				module.onTick();
			}
		}
	}

	public void onKeyPress(int key) {
		for (Module module : modules) {
			if (module.getKeyBind() == key) {
				module.onKeyBind();
			}
		}
	}
}
