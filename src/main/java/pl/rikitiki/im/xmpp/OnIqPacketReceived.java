package pl.rikitiki.im.xmpp;

import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.xmpp.stanzas.IqPacket;

public interface OnIqPacketReceived extends PacketReceived {
	void onIqPacketReceived(Account account, IqPacket packet);
}
