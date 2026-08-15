package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.GroupSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.HealthChangeTracker;
import unlucky.utility.client.util.PingSound;
import unlucky.utility.client.util.Render3D;

/**
 * Something visible happens where a hit lands.
 *
 * <p><b>The numbers stay with {@link HealthIndicators}.</b> Not a layering preference — a
 * division of one job. Both modules read the same {@link HealthChangeTracker} event, so they can
 * never disagree about what happened; what they must not do is both <em>say</em> it, because two
 * renderings of one hit read as two hits. This one is deliberately non-numeric for that reason,
 * and the pair is designed to be run together.
 *
 * <p><b>One event is one burst.</b> That is the tracker's guarantee, and it is the whole reason
 * this module is not allowed to diff health itself: a hit arrives over several packet paths — the
 * health update, the hurt animation, the sound — and a module watching for "something happened"
 * finds three of them.
 *
 * <p><b>The cap is a hard cap, checked before spawning.</b> A crystal in a crowd is a dozen
 * confirmed events in one tick, each asking for up to fifty particles; without a ceiling the
 * frame that mattered most is the one that stutters.
 */
public class HitEffects extends Module {
	/** Beyond this an effect is a few pixels of noise, and simulating it is pure cost. */
	private static final double MAX_DISTANCE_SQR = 48.0 * 48.0;

	/** Milliseconds per client tick, for turning the lifetime setting into steps. */
	private static final float TICK_MS = 50.0f;

	/** How much speed survives a bounce. Low on purpose: these settle, they do not rattle. */
	private static final double BOUNCE = 0.4;

	/** The four-stroke marker, shipped with the client. */
	private static final Identifier HITMARKER =
			Identifier.fromNamespaceAndPath("unlucky", "textures/gui/hitmarker.png");

	public final ModeSetting effect = add(new ModeSetting("Effect",
			"Particles scatter, Rings expand where the hit landed, Sparks streak outward",
			"Particles", "Particles", "Rings", "Sparks"));
	public final ModeSetting events = add(new ModeSetting("Events",
			"Which health changes get an effect", "Damage", "Damage", "Heal", "Both"));

	public final BooleanSetting self = add(new BooleanSetting("Self",
			"Show effects on yourself", false));
	public final BooleanSetting players = add(new BooleanSetting("Players",
			"Show effects on other players", true));
	public final BooleanSetting mobs = add(new BooleanSetting("Mobs",
			"Show effects on mobs", true));
	public final BooleanSetting ownHitsOnly = add(new BooleanSetting("Own hits only",
			"Only damage the tracker can plausibly attribute to you — a filter, not a guarantee",
			false));

	public final NumberSetting amount = add(new NumberSetting("Amount",
			"Effects spawned per event", 8, 1, 50, 1));
	public final NumberSetting lifetime = add(new NumberSetting("Lifetime",
			"Milliseconds an effect lives", 750, 100, 5000, 50));
	public final NumberSetting speed = add(new NumberSetting("Speed",
			"Initial velocity in blocks per tick", 0.10, 0.01, 1.00, 0.01));
	public final NumberSetting scale = add(new NumberSetting("Scale",
			"Visual size multiplier", 1.0, 0.1, 5.0, 0.1));
	public final NumberSetting gravity = add(new NumberSetting("Gravity",
			"Downward acceleration per tick; negative floats them up", 0.03, -0.20, 0.20, 0.01));
	public final BooleanSetting physics = add(new BooleanSetting("Physics",
			"Bounce and settle against the world instead of passing through it", true));

	public final ModeSetting colorMode = add(new ModeSetting("Color mode",
			"Damage/heal colours by event, Theme and Rainbow ignore it",
			"Damage/heal", "Static", "Damage/heal", "Theme", "Rainbow"));
	public final ColorSetting damageColor = add(new ColorSetting("Damage color",
			"Colour for damage events", 0xFFFF5555), () -> colorMode.is("Damage/heal"));
	public final ColorSetting healColor = add(new ColorSetting("Heal color",
			"Colour for healing events", 0xFF55FF55), () -> colorMode.is("Damage/heal"));
	public final ColorSetting staticColor = add(new ColorSetting("Static color",
			"One colour for everything", 0xFFB478FF),
			() -> colorMode.is("Static") || colorMode.is("Theme"));

	public final GroupSetting hitmarker = add(new GroupSetting("Hitmarker",
			"The four-stroke marker that flashes on the crosshair when you land a hit"));
	public final BooleanSetting showHitmarker = add(new BooleanSetting("Show hitmarker",
			"Flash a marker on the crosshair for hits you landed", false), hitmarker::isExpanded);
	public final NumberSetting hitmarkerDuration = add(new NumberSetting("Hitmarker duration",
			"Milliseconds the marker stays up", 300, 50, 2000, 50),
			() -> hitmarker.isExpanded() && showHitmarker.get());
	public final NumberSetting hitmarkerSize = add(new NumberSetting("Hitmarker size",
			"Marker size in pixels", 16, 4, 64, 1),
			() -> hitmarker.isExpanded() && showHitmarker.get());
	public final NumberSetting hitmarkerSpread = add(new NumberSetting("Hitmarker spread",
			"How far the strokes kick out from the crosshair as they fade; 0 holds still",
			2, 0, 16, 1), () -> hitmarker.isExpanded() && showHitmarker.get());
	public final ColorSetting hitmarkerColor = add(new ColorSetting("Hitmarker color",
			"Marker colour", 0xFFFFFFFF), () -> hitmarker.isExpanded() && showHitmarker.get());
	public final ColorSetting hitmarkerKillColor = add(new ColorSetting("Hitmarker kill color",
			"Marker colour when the hit was lethal", 0xFFFF5555),
			() -> hitmarker.isExpanded() && showHitmarker.get());
	public final BooleanSetting hitmarkerFade = add(new BooleanSetting("Hitmarker fade",
			"Fade the marker out rather than blinking it off", true),
			() -> hitmarker.isExpanded() && showHitmarker.get());

	public final ModeSetting sound = add(new ModeSetting("Sound",
			"Cue on each event. Hitmarker and Classic are this client's own samples; the rest "
					+ "are vanilla's.", "Off", "Off", "Hitmarker", "Classic", "Crit", "Pling"));
	public final BooleanSetting soundOwnHitsOnly = add(new BooleanSetting("Sound on own hits only",
			"Only make a noise for damage attributable to you, whatever the effects do",
			true), () -> !sound.is("Off"));
	public final NumberSetting soundVolume = add(new NumberSetting("Sound volume",
			"Hitsound volume", 1.0, 0.05, 2.0, 0.05), () -> !sound.is("Off"));
	public final ModeSetting soundPitch = add(new ModeSetting("Sound pitch",
			"Fixed, or scaled by how much damage the hit did", "Fixed", "Fixed", "By damage"),
			() -> !sound.is("Off"));
	public final NumberSetting soundPitchValue = add(new NumberSetting("Pitch",
			"Playback pitch", 1.0, 0.5, 2.0, 0.05),
			() -> !sound.is("Off") && soundPitch.is("Fixed"));

	public final NumberSetting maximumLive = add(new NumberSetting("Maximum live effects",
			"Hard ceiling on simulated effects", 256, 32, 1000, 8));

	/**
	 * One live effect.
	 *
	 * <p>World coordinates, not an entity offset like HealthIndicators uses: these outlive the
	 * hit and are supposed to stay where they were thrown, so following the mob that was hit
	 * would drag the whole burst along behind it.
	 */
	private static final class Effect {
		double x;
		double y;
		double z;
		double vx;
		double vy;
		double vz;
		int age;
		int life;
		int color;
		boolean settled;
	}

	private final List<Effect> live = new ArrayList<>();
	private final Random rng = new Random();

	/** When the marker was last triggered, and whether that hit finished the target off. */
	private long hitmarkerAt;
	private boolean hitmarkerLethal;

	public HitEffects() {
		super("HitEffects", "Particles, a hitmarker and hitsounds where hits land", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		HealthChangeTracker.addConsumer(this);
		live.clear();
		hitmarkerAt = 0L;
	}

	@Override
	protected void onDisable() {
		HealthChangeTracker.removeConsumer(this);
		live.clear();
		hitmarkerAt = 0L;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			live.clear();
			hitmarkerAt = 0L;
			return;
		}
		for (HealthChangeTracker.Event event : HealthChangeTracker.events()) {
			// The three responses are gated separately on purpose. A player who wants a hitmarker
			// for their own hits and particles on everything they can see is asking for something
			// coherent, and one shared filter would refuse it.
			if (wants(event)) {
				spawn(event);
			}
			markHit(event);
			playSound(event);
		}
		step();
		render();
	}

	/**
	 * The hitmarker's trigger.
	 *
	 * <p>Always "your own hits", with no setting to widen it: a marker on the crosshair means
	 * <em>you</em> connected, and one that lit up because somebody across the map shot somebody
	 * else would be worse than no marker at all. That it is only ever as reliable as
	 * {@link HealthChangeTracker}'s attribution is a real limit, and the reason that method is
	 * named the way it is.
	 */
	private void markHit(HealthChangeTracker.Event event) {
		if (!showHitmarker.get() || !event.damage() || !event.attributableToSelf()) {
			return;
		}
		hitmarkerAt = System.currentTimeMillis();
		// The entity is still alive on the tick the killing blow lands, so "lethal" is a
		// prediction from the health that is left, not an observation of a death.
		hitmarkerLethal = event.entity().getHealth() - event.amount() <= 0.0f
				|| !event.entity().isAlive();
	}

	/**
	 * The hitsound.
	 *
	 * <p>Pitch-by-damage maps the hit onto the playable half of the range rather than the whole
	 * of it: a sound that drops to 0.5 for a scratch is quieter-sounding as well as lower, and
	 * the point of the cue is that every hit is audible.
	 */
	private void playSound(HealthChangeTracker.Event event) {
		if (sound.is("Off") || !event.damage()) {
			return;
		}
		if (soundOwnHitsOnly.get() && !event.attributableToSelf()) {
			return;
		}
		float pitch = soundPitch.is("By damage")
				? Mth.clamp(0.8f + event.amount() / 12.0f, 0.8f, 1.8f)
				: soundPitchValue.getFloat();
		PingSound.play(sound.get(), pitch, soundVolume.getFloat());
	}

	/** Every reason an event does or does not earn a burst, in one place. */
	private boolean wants(HealthChangeTracker.Event event) {
		LivingEntity living = event.entity();
		if (living == null || mc().player.distanceToSqr(living) > MAX_DISTANCE_SQR) {
			return false;
		}
		if (event.damage() ? events.is("Heal") : events.is("Damage")) {
			return false;
		}
		if (ownHitsOnly.get() && (!event.damage() || !event.attributableToSelf())) {
			return false;
		}
		if (living == mc().player) {
			return self.get();
		}
		return CombatUtil.validTarget(living, players.get(), mobs.get(), mobs.get());
	}

	/**
	 * Throws one burst out of the middle of whoever was hit.
	 *
	 * <p>The cap is enforced per particle rather than per burst, and it drops the oldest: a
	 * ceiling that refused the whole burst would make the effects vanish entirely exactly when
	 * the fight got interesting, which is the opposite of what a hit marker is for.
	 */
	private void spawn(HealthChangeTracker.Event event) {
		LivingEntity living = event.entity();
		int color = colorFor(event);
		int count = amount.getInt();
		int life = Math.max(1, Math.round(lifetime.getFloat() / TICK_MS));
		double origin = living.getY() + living.getBbHeight() * 0.5;
		double launch = speed.get();

		for (int i = 0; i < count; i++) {
			if (live.size() >= maximumLive.getInt()) {
				live.remove(0);
			}
			Effect particle = new Effect();
			particle.x = living.getX() + (rng.nextDouble() - 0.5) * living.getBbWidth();
			particle.y = origin + (rng.nextDouble() - 0.5) * living.getBbHeight() * 0.5;
			particle.z = living.getZ() + (rng.nextDouble() - 0.5) * living.getBbWidth();
			if (effect.is("Rings")) {
				// A ring is a burst with no vertical component and a fixed radius per particle,
				// so it reads as one expanding shape rather than as a cloud.
				double angle = i * (Math.PI * 2.0 / count);
				particle.x = living.getX();
				particle.y = origin;
				particle.z = living.getZ();
				particle.vx = Math.cos(angle) * launch;
				particle.vy = 0.0;
				particle.vz = Math.sin(angle) * launch;
			} else {
				particle.vx = (rng.nextDouble() - 0.5) * 2.0 * launch;
				particle.vy = rng.nextDouble() * launch;
				particle.vz = (rng.nextDouble() - 0.5) * 2.0 * launch;
			}
			particle.life = life;
			particle.color = color;
			live.add(particle);
		}
	}

	/** One tick of movement for every live effect, and the expiry sweep. */
	private void step() {
		double pull = effect.is("Rings") ? 0.0 : gravity.get();
		for (Iterator<Effect> it = live.iterator(); it.hasNext();) {
			Effect particle = it.next();
			if (++particle.age >= particle.life) {
				it.remove();
				continue;
			}
			if (particle.settled) {
				continue;
			}
			particle.vy -= pull;
			if (physics.get() && !effect.is("Rings")) {
				move(particle);
			} else {
				particle.x += particle.vx;
				particle.y += particle.vy;
				particle.z += particle.vz;
			}
			// Rings keep expanding at a constant rate; everything else sheds speed to air.
			if (!effect.is("Rings")) {
				particle.vx *= 0.96;
				particle.vz *= 0.96;
			}
		}
	}

	/**
	 * Moves one particle with the world in the way.
	 *
	 * <p>Axis at a time, which is what makes a particle slide along a wall instead of stopping
	 * dead against it, and what lets the vertical bounce be told apart from a horizontal one.
	 * A particle that has stopped is parked rather than kept in the simulation — a hundred of
	 * them jittering on the floor costs the same as a hundred of them flying.
	 */
	private void move(Effect particle) {
		double half = 0.03;
		if (free(particle.x + particle.vx, particle.y, particle.z, half)) {
			particle.x += particle.vx;
		} else {
			particle.vx = -particle.vx * BOUNCE;
		}
		if (free(particle.x, particle.y + particle.vy, particle.z, half)) {
			particle.y += particle.vy;
		} else {
			particle.vy = -particle.vy * BOUNCE;
			if (Math.abs(particle.vy) < 0.01) {
				particle.settled = true;
			}
		}
		if (free(particle.x, particle.y, particle.z + particle.vz, half)) {
			particle.z += particle.vz;
		} else {
			particle.vz = -particle.vz * BOUNCE;
		}
	}

	private boolean free(double x, double y, double z, double half) {
		return mc().level.noCollision(new AABB(x - half, y - half, z - half,
				x + half, y + half, z + half));
	}

	private int colorFor(HealthChangeTracker.Event event) {
		return switch (colorMode.get()) {
			case "Static", "Theme" -> staticColor.get();
			case "Rainbow" -> ColorUtil.hsb((System.currentTimeMillis() % 3000L) / 3000.0f,
					0.7f, 1.0f, 255);
			default -> event.damage() ? damageColor.get() : healColor.get();
		};
	}

	/**
	 * Emits this tick's geometry.
	 *
	 * <p>Gizmos live for one tick and are re-emitted, so this runs from {@code onTick} alongside
	 * the simulation rather than from the per-frame overlay — which also means the effect and the
	 * position it is drawn at can never be a tick out of step with each other.
	 */
	private void render() {
		if (live.isEmpty()) {
			return;
		}
		double size = 0.05 * scale.get();
		boolean sparks = effect.is("Sparks");
		for (Effect particle : live) {
			float remaining = 1.0f - (float) particle.age / particle.life;
			int color = ColorUtil.multiplyAlpha(particle.color, Mth.clamp(remaining, 0.0f, 1.0f));
			if (color == 0) {
				continue;
			}
			if (sparks) {
				// A spark is its own last few ticks of travel, drawn as the streak it made.
				Vec3 head = new Vec3(particle.x, particle.y, particle.z);
				Vec3 tail = head.subtract(particle.vx, particle.vy, particle.vz);
				Render3D.line(head, tail, color, (float) Math.max(1.0, scale.get()), true);
				continue;
			}
			Render3D.box(new AABB(particle.x - size, particle.y - size, particle.z - size,
					particle.x + size, particle.y + size, particle.z + size),
					0, 1.0f, color, true);
		}
	}

	/**
	 * The hitmarker, on the crosshair, every frame.
	 *
	 * <p>Drawn from the HUD layer rather than as a gizmo because that is what it is — a 2D mark
	 * on the centre of the screen, not a thing in the world. It is also the one part of this
	 * module that has to be per-frame: a 300ms flash sampled at 20Hz is six frames of animation,
	 * and the fade would visibly step.
	 *
	 * <p>Not drawn while a screen is open. The crosshair is not there either, and a marker
	 * hanging in the middle of your inventory is a bug however it got there.
	 */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!isEnabled() || !showHitmarker.get() || hitmarkerAt == 0L || mc().gui.screen() != null) {
			return;
		}
		long age = System.currentTimeMillis() - hitmarkerAt;
		int duration = hitmarkerDuration.getInt();
		if (age >= duration) {
			hitmarkerAt = 0L;
			return;
		}
		float remaining = 1.0f - (float) age / duration;
		int base = hitmarkerLethal ? hitmarkerKillColor.get() : hitmarkerColor.get();
		int color = hitmarkerFade.get() ? ColorUtil.multiplyAlpha(base, remaining) : base;
		if (ColorUtil.alpha(color) <= 2) {
			return;
		}

		// The classic kick: the strokes start tight and spread as they fade, which is what makes
		// the mark read as an impact rather than as an icon appearing.
		int size = hitmarkerSize.getInt() + Math.round(hitmarkerSpread.getFloat() * (1.0f - remaining));
		int x = g.guiWidth() / 2 - size / 2;
		int y = g.guiHeight() / 2 - size / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, HITMARKER, x, y, 0.0f, 0.0f, size, size, size, size, color);
	}

	/** How many effects are alive, for the debug read-out. */
	public int liveCount() {
		return live.size();
	}

	/** Whether the marker is on screen right now, for the debug read-out. */
	public boolean hitmarkerVisible() {
		return hitmarkerAt != 0L
				&& System.currentTimeMillis() - hitmarkerAt < hitmarkerDuration.getInt();
	}
}
