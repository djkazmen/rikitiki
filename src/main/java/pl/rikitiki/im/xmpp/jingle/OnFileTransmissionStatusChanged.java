package pl.rikitiki.im.xmpp.jingle;

import pl.rikitiki.im.entities.DownloadableFile;

public interface OnFileTransmissionStatusChanged {
	void onFileTransmitted(DownloadableFile file);

	void onFileTransferAborted();
}
