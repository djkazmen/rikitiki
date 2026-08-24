package pl.rikitiki.im.xmpp.jingle;

import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.xmpp.jid.Jid;
import pl.rikitiki.im.xmpp.jingle.stanzas.JinglePacket;

/**
 * Common shape shared by {@link JingleConnection} (file transfer) and
 * {@link JingleRtpConnection} (audio calls), so {@link JingleConnectionManager}
 * can route non-session-initiate Jingle packets to either kind of session
 * through a single lookup instead of two parallel connection lists.
 */
public interface JingleSession {
	Account getAccount();

	String getSessionId();

	Jid getCounterPart();

	void deliverPacket(JinglePacket packet);
}
