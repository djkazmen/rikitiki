package pl.rikitiki.im.xmpp.stanzas.csi;

import pl.rikitiki.im.xmpp.stanzas.AbstractStanza;

public class ActivePacket extends AbstractStanza {
	public ActivePacket() {
		super("active");
		setAttribute("xmlns", "urn:xmpp:csi:0");
	}
}
