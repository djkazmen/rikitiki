package pl.rikitiki.im.xmpp;

import pl.rikitiki.im.entities.Account;

public interface OnMessageAcknowledged {
	public void onMessageAcknowledged(Account account, String id);
}
