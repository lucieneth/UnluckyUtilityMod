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
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.module.modules.misc.AntiToS;
import unlucky.utility.client.module.modules.misc.BookTools;
import unlucky.utility.client.module.modules.misc.InventoryInfo;
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
import unlucky.utility.client.module.modules.movement.AntiLevitation;
import unlucky.utility.client.module.modules.movement.ClickTP;
import unlucky.utility.client.module.modules.movement.Jesus;
import unlucky.utility.client.module.modules.movement.NoFall;
import unlucky.utility.client.module.modules.movement.TridentFly;
import unlucky.utility.client.module.modules.movement.Yaw;
import unlucky.utility.client.module.modules.player.AntiHunger;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.modules.player.AutoExtinguish;
import unlucky.utility.client.module.modules.player.AutoFish;
import unlucky.utility.client.module.modules.player.Capes;
import unlucky.utility.client.module.modules.player.FastUse;
import unlucky.utility.client.module.modules.player.AutoXPRepair;
import unlucky.utility.client.module.modules.player.Honker;
import unlucky.utility.client.module.modules.player.PagePirate;
import unlucky.utility.client.module.modules.render.AutoDrawDistance;
import unlucky.utility.client.module.modules.render.Chams;
import unlucky.utility.client.module.modules.render.ElytraPhysics;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.module.modules.render.MobESP;
import unlucky.utility.client.module.modules.render.NoFog;
import unlucky.utility.client.module.modules.render.NoRender;
import unlucky.utility.client.module.modules.render.NoWeather;
import unlucky.utility.client.module.modules.render.ViewClip;
import unlucky.utility.client.module.modules.render.NameTags;
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
import unlucky.utility.client.module.modules.world.Nuker;
import unlucky.utility.client.module.modules.world.Search;
import unlucky.utility.client.module.modules.world.TreasureESP;
import unlucky.utility.client.module.modules.world.VanityESP;
import unlucky.utility.client.module.modules.world.ChatSigns;
import unlucky.utility.client.module.modules.world.WaxAura;
import unlucky.utility.client.util.PerfDebug;

public final class ModuleManager {
	private final List<Module> modules = new ArrayList<>();
	// get(Class) sits on per-entity-per-frame render paths (chams, glow, nametag
	// hiding), so it must be a map lookup, not a scan over the module list
	private final java.util.Map<Class<?>, Module> byClass = new java.util.IdentityHashMap<>();

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
		register(new NameTags());
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
		register(new Search());
		register(new Nuker());
		register(new Archaeology());
		register(new Aura());
		register(new TriggerBot());
		register(new AutoClicker());
		register(new XRay());
		register(new NoFall());
		register(new AntiHunger());
		register(new FastUse());
		register(new AntiLevitation());
		register(new Yaw());
		register(new NoWeather());
		register(new ViewClip());
		register(new NoRender());
		register(new Jesus());
		register(new TridentFly());
		register(new ClickTP());
		register(new AutoEat());
		register(new AutoFish());
		register(new InventoryInfo());
		register(new Friends());
		modules.sort(Comparator.comparing(Module::getName));
	}

	private void register(Module module) {
		modules.add(module);
		byClass.put(module.getClass(), module);
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
		Module module = byClass.get(type);
		if (module == null) {
			throw new IllegalStateException("Module not registered: " + type.getSimpleName());
		}
		return (T) module;
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
				long start = PerfDebug.ENABLED ? PerfDebug.begin() : 0L;
				module.onTick();
				if (PerfDebug.ENABLED) {
					PerfDebug.end("tick." + module.getName(), start);
				}
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
