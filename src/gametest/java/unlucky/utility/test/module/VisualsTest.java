package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.render.BetterTab;
import unlucky.utility.client.module.modules.render.BlockOutline;
import unlucky.utility.client.module.modules.render.Breadcrumbs;
import unlucky.utility.client.module.modules.render.HitEffects;
import unlucky.utility.client.module.modules.render.ItemESP;
import unlucky.utility.client.module.modules.render.Tracers;
import unlucky.utility.client.module.modules.render.ViewModel;
import unlucky.utility.client.util.PingSound;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;
import static unlucky.utility.test.module.ModuleTestSupport.DWELL;

/**
 * Tab, outline, arm, tracer and item-delegation contracts for the render modules.
 */
public final class VisualsTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(BetterTab.class, BlockOutline.class, Breadcrumbs.class, HitEffects.class, ItemESP.class, Tracers.class, ViewModel.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyVisualContracts(context);
	}

	/**
	 * The visual-polish modules, checked for the property a render sweep cannot see.
	 *
	 * <p>All five of these join a vanilla path that runs whether they are on or not — the tab
	 * list's row assembly, the selected-block outline, the first-person arm, Shader's silhouette
	 * pass. A disabled one is therefore not idle: it is still being asked, every frame, and has to
	 * answer with exactly what vanilla would have done. That failure renders perfectly and is
	 * simply <em>wrong</em>, which is precisely what the "did it throw" sweep is blind to.
	 *
	 * <p>ItemESP gets the extra half: its filter has to give labels, tracers and the delegated
	 * silhouette the same answer, and the silhouette is the one of the three that leaves the
	 * module — so it is the one that can silently disagree.
	 */
	private void verifyVisualContracts(ClientGameTestContext context) {
		List<String> failures = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();

			// BetterTab: transparent when off, in every one of its answers.
			BetterTab betterTab = UnluckyClient.INSTANCE.modules.get(BetterTab.class);
			boolean wasTab = betterTab.isEnabled();
			betterTab.setEnabledSilently(false);
			List<net.minecraft.client.multiplayer.PlayerInfo> rows =
					new ArrayList<>(mc.getConnection().getListedOnlinePlayers());
			if (betterTab.arrange(rows) != rows) {
				problems.add("a disabled BetterTab rebuilt the tab-list row list");
			}
			if (!betterTab.showsHeads() || !betterTab.showsScore() || !betterTab.showsHeaderFooter()
					|| !betterTab.showsPingBars() || betterTab.showsExactLatency()
					|| !betterTab.keepsVanillaGamemodeColor()) {
				problems.add("a disabled BetterTab still changes what the tab list draws");
			}
			for (var info : rows) {
				if (betterTab.rowTint(info) != 0) {
					problems.add("a disabled BetterTab still tints a row");
				}
			}
			betterTab.setEnabledSilently(wasTab);

			// ViewModel: the arm transform is identity, and so are the two scalars.
			ViewModel viewModel = UnluckyClient.INSTANCE.modules.get(ViewModel.class);
			boolean wasViewModel = viewModel.isEnabled();
			viewModel.setEnabledSilently(false);
			if (viewModel.equipProgress(0.37f) != 0.37f || viewModel.swingProgress(0.62f) != 0.62f
					|| !viewModel.showsUseAnimation()) {
				problems.add("a disabled ViewModel still alters the first-person arm");
			}
			viewModel.setEnabledSilently(true);
			// Enabled but untouched is still identity: the defaults must not move anybody's hands
			// the first time they toggle it on to see what it does.
			if (viewModel.equipProgress(0.37f) != 0.37f || viewModel.swingProgress(0.62f) != 0.62f) {
				problems.add("ViewModel's defaults are not the vanilla arm");
			}
			viewModel.setEnabledSilently(wasViewModel);

			// BlockOutline: off means vanilla's own submission, unchanged in all four arguments.
			BlockOutline outline = UnluckyClient.INSTANCE.modules.get(BlockOutline.class);
			boolean wasOutline = outline.isEnabled();
			outline.setEnabledSilently(false);
			VoxelShape shape = Shapes.block();
			BlockOutline.Decision decision = outline.decide(mc.player.blockPosition(), shape, 0x40FF00FF, 2.5f);
			if (!decision.draw() || decision.shape() != shape || decision.color() != 0x40FF00FF
					|| decision.width() != 2.5f) {
				problems.add("a disabled BlockOutline changed vanilla's outline submission");
			}
			outline.setEnabledSilently(wasOutline);

			// ItemESP: Shader must hear 0 unless the module is on *and* delegating, and the
			// answer it does hear has to come from the same filter the labels use.
			ItemESP itemEsp = UnluckyClient.INSTANCE.modules.get(ItemESP.class);
			boolean wasItemEsp = itemEsp.isEnabled();
			ItemEntity drop = null;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof ItemEntity found) {
					drop = found;
				}
			}
			if (drop != null) {
				itemEsp.setEnabledSilently(false);
				if (itemEsp.silhouetteColor(drop) != 0) {
					problems.add("a disabled ItemESP still claims items from Shader");
				}
				itemEsp.setEnabledSilently(true);
				boolean wasSilhouette = itemEsp.silhouette.get();
				itemEsp.silhouette.set(false);
				if (itemEsp.silhouetteColor(drop) != 0) {
					problems.add("ItemESP claimed a silhouette with the Silhouette switch off");
				}
				itemEsp.silhouette.set(true);
				if (itemEsp.silhouetteColor(drop) == 0) {
					problems.add("ItemESP delegated nothing for an item its labels would show");
				}
				String previousFilter = itemEsp.filter.get();
				itemEsp.filter.set("Whitelist");
				itemEsp.items.clear();
				if (itemEsp.silhouetteColor(drop) != 0) {
					problems.add("ItemESP's silhouette ignored the filter its labels obey");
				}
				itemEsp.filter.set(previousFilter);
				itemEsp.silhouette.set(wasSilhouette);
				itemEsp.setEnabledSilently(wasItemEsp);
			}

			// HitEffects: no events, no effects — and the cap is a number, not a hope.
			HitEffects hitEffects = UnluckyClient.INSTANCE.modules.get(HitEffects.class);
			if (hitEffects.liveCount() > hitEffects.maximumLive.getInt()) {
				problems.add("HitEffects is holding more effects than its cap allows");
			}

			// The assets this client ships itself. A sound or texture that did not make it into
			// the jar fails at the moment it is needed — mid-fight, as silence and a missing
			// marker — and nothing before that point complains. Asking the loaded resource pack
			// and the sound registry directly turns that into a build failure.
			for (String path : new String[]{"textures/gui/hitmarker.png",
					"sounds/hitmarker.ogg", "sounds/hit1.ogg", "sounds.json"}) {
				if (mc.getResourceManager()
						.getResource(Identifier.fromNamespaceAndPath("unlucky", path)).isEmpty()) {
					problems.add("shipped asset unlucky:" + path + " is not in the resource pack");
				}
			}
			for (var event : new net.minecraft.sounds.SoundEvent[]{
					PingSound.HITMARKER, PingSound.HIT}) {
				if (mc.getSoundManager().getSoundEvent(event.location()) == null) {
					problems.add("sounds.json does not define " + event.location()
							+ ", so the hitsound would play nothing");
				}
			}
			Breadcrumbs breadcrumbs = UnluckyClient.INSTANCE.modules.get(Breadcrumbs.class);
			if (breadcrumbs.pointCount() > breadcrumbs.maximumPoints.getInt() * 2) {
				problems.add("Breadcrumbs is holding more points than its two ring buffers allow");
			}
			return problems;
		});

		// Exercise a real tracer before the isolated module sweep has had a chance to move the
		// player or hurt the scene mobs. Aim the camera at the known hostile so this checks target
		// selection, interpolation, projection and line submission rather than off-screen policy.
		TracerProbe tracerProbe = context.computeOnClient(mc -> {
			Entity hostile = null;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof net.minecraft.world.entity.monster.Enemy) {
					hostile = entity;
					break;
				}
			}
			if (hostile == null) {
				return null;
			}

			Tracers tracers = UnluckyClient.INSTANCE.modules.get(Tracers.class);
			Vec3 eye = mc.player.getEyePosition();
			Vec3 aim = hostile.getBoundingBox().getCenter();
			double dx = aim.x - eye.x;
			double dy = aim.y - eye.y;
			double dz = aim.z - eye.z;
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
			TracerProbe probe = new TracerProbe(tracers, tracers.isEnabled(), tracers.hostiles.get(),
					mc.player.getYRot(), mc.player.getXRot());
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			tracers.hostiles.set(true);
			tracers.setEnabledSilently(true);
			return probe;
		});
		if (tracerProbe == null) {
			failures.add("the fresh scene had no hostile for Tracers");
		} else {
			context.waitTicks(DWELL);
			if (context.computeOnClient(mc -> tracerProbe.module().tracerCount()) < 1) {
				failures.add("Tracers selected no visible line for the fresh-scene zombie");
			}
			context.runOnClient(mc -> {
				tracerProbe.module().setEnabledSilently(tracerProbe.enabled());
				tracerProbe.module().hostiles.set(tracerProbe.hostiles());
				mc.player.setYRot(tracerProbe.yaw());
				mc.player.setXRot(tracerProbe.pitch());
			});
			context.waitTick();
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("Visual-polish contracts failed: " + String.join("; ", failures));
		}
		LOGGER.info("[visuals] tab, outline, arm, tracer and item-delegation contracts hold");
	}

	private record TracerProbe(Tracers module, boolean enabled, boolean hostiles, float yaw, float pitch) {
	}
}
