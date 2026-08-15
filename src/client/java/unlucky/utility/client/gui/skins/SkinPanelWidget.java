package unlucky.utility.client.gui.skins;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * The shared half of the two title-screen skin previews: drag to spin, head follows the mouse,
 * no keyboard focus and no click sound.
 *
 * <p><b>Horizontal only, and that is a decision rather than a shortcut.</b> A preview you can
 * tip over is a preview you have to put back, and there is no obvious "reset" gesture on a
 * title screen. Yaw is the axis that shows you the thing you actually want to see — the back of
 * the skin and the cape — so it is the axis that moves; the vertical drag is dropped on the
 * floor rather than accumulated invisibly.
 *
 * <p><b>The spin is wrapped, not clamped.</b> Clamping would stop the model dead half way round
 * and read as the drag having broken; wrapping means you can keep turning in one direction for
 * ever, which is what a turntable does.
 *
 * <p>Existing only to be dragged, this deliberately still takes no focus: tab-cycling onto a
 * decoration and getting a highlight ring around it helps nobody.
 */
public abstract class SkinPanelWidget extends AbstractWidget {
	/** Degrees of spin per pixel dragged. Roughly a full turn across the panel's width. */
	private static final float DEGREES_PER_PIXEL = 3.0f;

	/** Accumulated spin from dragging, in degrees, wrapped to (-180, 180]. */
	private float dragYaw;

	protected SkinPanelWidget(int x, int y, int width, int height) {
		super(x, y, width, height, CommonComponents.EMPTY);
	}

	/** Whose skin this panel shows. Resolved per frame so a switch is picked up immediately. */
	protected abstract PlayerSkin skin();

	/** Whether this panel's stance is the mirrored one — see {@link SkinRender}. */
	protected boolean mirrored() {
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		SkinRender.draw(g, skin(), mirrored(), dragYaw,
				getX(), getY(), getWidth(), getHeight(), mouseX, mouseY);
	}

	/**
	 * Only the horizontal component is used. The vertical is not stored and not applied — see the
	 * class note; dropping it here rather than ignoring it at render time means there is no hidden
	 * pitch waiting to appear if somebody later "fixes" the render.
	 */
	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		dragYaw = Mth.wrapDegrees(dragYaw + (float) dragX * DEGREES_PER_PIXEL);
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	@Override
	public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent navigationEvent) {
		return null;
	}
}
