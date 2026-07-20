package pl.rikitiki.im.crypto.axolotl;

public interface OnMessageCreatedCallback {
	void run(XmppAxolotlMessage message);
}
