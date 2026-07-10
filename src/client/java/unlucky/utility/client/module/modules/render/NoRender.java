package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * The annoyance killer: independent toggles for things vanilla draws over your
 * view. Each is its own small hook — see {@code ScreenEffectRendererMixin},
 * {@code BossHealthOverlayMixin}, {@code ClientLevelMixin} and
 * {@code FogRendererMixin}. Designed to grow; a new toggle should be one setting
 * plus one injection.
 *
 * <p>Split of responsibility: fog caused by <em>what's happening to you</em>
 * (water, lava, powder snow, blindness, darkness) belongs here; fog caused by
 * <em>where you are</em> (render distance, Nether, End) belongs to <b>NoFog</b>.
 * The hurt tilt is <b>NoHurtCam</b>'s.
 */
public class NoRender extends Module {
	public final BooleanSetting fireOverlay = add(new BooleanSetting("Fire overlay",
			"The flames across your screen while burning", true));
	public final BooleanSetting blockOverlay = add(new BooleanSetting("Block overlay",
			"The texture drawn over your view inside a block (pumpkin, powder snow)", true));
	public final BooleanSetting waterOverlay = add(new BooleanSetting("Water overlay",
			"The blue tint drawn over your view underwater", false));
	public final BooleanSetting totemAnimation = add(new BooleanSetting("Totem animation",
			"The giant totem that swings up the screen when one pops", true));
	public final BooleanSetting bossBars = add(new BooleanSetting("Boss bars",
			"The wither/dragon health bars across the top of the screen", false));
	public final BooleanSetting breakParticles = add(new BooleanSetting("Break particles",
			"The block-shard particles thrown out when a block breaks", false));

	public final BooleanSetting waterFog = add(new BooleanSetting("Water fog",
			"The blue haze that shortens your view underwater", true));
	public final BooleanSetting lavaFog = add(new BooleanSetting("Lava fog",
			"The orange haze inside lava", true));
	public final BooleanSetting powderSnowFog = add(new BooleanSetting("Powder snow fog",
			"The white haze inside powder snow", true));
	public final BooleanSetting blindnessFog = add(new BooleanSetting("Blindness fog",
			"The fog closed in by the blindness effect", true));
	public final BooleanSetting darknessFog = add(new BooleanSetting("Darkness fog",
			"The pulsing fog from a warden's darkness effect", true));

	public NoRender() {
		super("NoRender", "Turns off screen clutter and situational fog", Category.RENDER);
	}
}
