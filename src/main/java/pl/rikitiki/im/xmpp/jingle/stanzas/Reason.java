package pl.rikitiki.im.xmpp.jingle.stanzas;

import pl.rikitiki.im.xml.Element;

public class Reason extends Element {
	private Reason(String name) {
		super(name);
	}

	public Reason() {
		super("reason");
	}
}
