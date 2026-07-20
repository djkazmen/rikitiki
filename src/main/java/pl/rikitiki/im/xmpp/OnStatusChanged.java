package pl.rikitiki.im.xmpp;

import pl.rikitiki.im.entities.Account;

public interface OnStatusChanged {
	public void onStatusChanged(Account account);
}
