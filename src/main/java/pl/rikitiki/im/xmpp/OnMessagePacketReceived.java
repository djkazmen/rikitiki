package pl.rikitiki.im.xmpp;

import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.xmpp.stanzas.MessagePacket;

public interface OnMessagePacketReceived extends PacketReceived {
	public void onMessagePacketReceived(Account account, MessagePacket packet);
}
