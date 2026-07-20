package pl.rikitiki.im.xmpp;

import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.xmpp.stanzas.PresencePacket;

public interface OnPresencePacketReceived extends PacketReceived {
	public void onPresencePacketReceived(Account account, PresencePacket packet);
}
