package pl.rikitiki.im.xmpp;

import pl.rikitiki.im.entities.Contact;

public interface OnContactStatusChanged {
	public void onContactStatusChanged(final Contact contact, final boolean online);
}
