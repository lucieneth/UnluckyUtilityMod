package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Heads;
import unlucky.utility.client.util.GuiMessageSender;

/**
 * Chat heads, layout half: messages with a known sender wrap {@link Heads#INDENT}px
 * narrower and every produced line gets a spacer prefix, so the reserved gap is
 * part of the text itself — hover, click and tag-icon x-math stay native. The
 * first line is registered with {@link Heads} for the face draw. Runs again
 * automatically on chat rescale/re-flow since vanilla re-splits from here.
 */
@Mixin(GuiMessage.class)
public abstract class GuiMessageMixin implements GuiMessageSender {
	@Unique
	private UUID unlucky$sender;

	@Override
	public UUID unlucky$sender() {
		return unlucky$sender;
	}

	@Override
	public void unlucky$setSender(UUID sender) {
		unlucky$sender = sender;
	}

	@ModifyVariable(method = "splitLines", at = @At("HEAD"), argsOnly = true)
	private int unlucky$reserveHeadSpace(int maxWidth) {
		Heads heads = UnluckyClient.INSTANCE.modules.get(Heads.class);
		return heads.indents(unlucky$sender) ? maxWidth - Heads.INDENT : maxWidth;
	}

	@ModifyReturnValue(method = "splitLines", at = @At("RETURN"))
	private List<FormattedCharSequence> unlucky$indentLines(List<FormattedCharSequence> lines) {
		Heads heads = UnluckyClient.INSTANCE.modules.get(Heads.class);
		return heads.indents(unlucky$sender) ? heads.indentLines(lines, unlucky$sender) : lines;
	}
}
