package unlucky.utility.client.gui.alts;

import java.util.UUID;

import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import unlucky.utility.client.gui.skins.SkinPanelWidget;
import unlucky.utility.client.gui.skins.SkinRender;
import unlucky.utility.client.util.alts.AccountSwitcher;
import unlucky.utility.client.util.alts.AltAccount;
import unlucky.utility.client.util.alts.AltManager;

/**
 * The alt-switcher title-screen preview, mirroring the skin changer's on the
 * other side of the menu column — same model, same cape, same head-follows-the-
 * mouse and the same drag-to-spin, with the stance flipped so the two figures
 * lean toward the buttons between them rather than both leaning the same way.
 *
 * <p><b>It shows the account you are on, when that is one of yours.</b> Selecting
 * an alt swaps the session, so the preview follows: active alt first, then the
 * first saved one, then {@link #DEFAULT_NAME} when the list is empty. The old
 * empty-list placeholder was a zombie texture on the player model; a real profile
 * is both friendlier and honest about what the panel is for.
 *
 * <p>Everything comes from the vanilla skin render cache, which resolves
 * asynchronously and hands back Steve until the download lands — including the
 * model type, so a slim skin gets Alex arms instead of a guess.
 */
public class AltPreviewWidget extends SkinPanelWidget {
	/** Shown when no alts are saved. Resolved by name, so there is no uuid here to go stale. */
	private static final String DEFAULT_NAME = "LucienETH";

	public AltPreviewWidget(int x, int y, int width, int height) {
		super(x, y, width, height);
	}

	@Override
	protected boolean mirrored() {
		return true;
	}

	@Override
	protected PlayerSkin skin() {
		return SkinRender.skinOf(profile());
	}

	/**
	 * Whose skin to show.
	 *
	 * <p>The active session wins only when it is an account this manager knows about: after a
	 * switch that is the alt you picked, which is the whole point. On your own account it falls
	 * through to the saved list, so the panel keeps advertising what it is rather than duplicating
	 * the preview on the other side.
	 */
	private static ResolvableProfile profile() {
		UUID active = AccountSwitcher.activeUuid();
		if (active != null) {
			for (AltAccount account : AltManager.accounts()) {
				if (account.uuid().equals(active)) {
					return ResolvableProfile.createUnresolved(active);
				}
			}
		}
		AltAccount first = AltManager.first();
		return first != null
				? ResolvableProfile.createUnresolved(first.uuid())
				: ResolvableProfile.createUnresolved(DEFAULT_NAME);
	}
}
