package unlucky.utility.client.util;

import java.util.UUID;

/**
 * Duck interface stitched onto {@code GuiMessage} by {@code GuiMessageMixin}:
 * the resolved sender UUID for chat heads. Records tolerate mixin-added fields
 * fine — the field just isn't a record component. Null = no head.
 */
public interface GuiMessageSender {
	UUID unlucky$sender();

	void unlucky$setSender(UUID sender);
}
