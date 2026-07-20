package pl.rikitiki.im.services;

import pl.rikitiki.im.entities.Account;

public class PushManagementService {

	protected final XmppConnectionService mXmppConnectionService;

	public PushManagementService(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	public void registerPushTokenOnServer(Account account) {
		//stub implementation; push notifications are not supported
	}

	public boolean available(Account account) {
		return false;
	}

	public boolean isStub() {
		return true;
	}

	public boolean availableAndUseful(Account account) {
		return false;
	}
}
