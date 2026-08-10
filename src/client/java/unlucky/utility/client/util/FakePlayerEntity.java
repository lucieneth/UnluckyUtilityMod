package unlucky.utility.client.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;

/** Stable marker type for client-only practice players; never enters the network player list. */
public final class FakePlayerEntity extends RemotePlayer {
	public FakePlayerEntity(ClientLevel level, GameProfile profile) {
		super(level, profile);
	}
}
