package pl.rikitiki.im.entities;

import pl.rikitiki.im.xmpp.jid.Jid;

public interface Blockable {
	boolean isBlocked();
	boolean isDomainBlocked();
	Jid getBlockedJid();
	Jid getJid();
	Account getAccount();
}
