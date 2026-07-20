package pl.rikitiki.im.xmpp.jingle;

import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.xmpp.PacketReceived;
import pl.rikitiki.im.xmpp.jingle.stanzas.JinglePacket;

public interface OnJinglePacketReceived extends PacketReceived {
	void onJinglePacketReceived(Account account, JinglePacket packet);
}
