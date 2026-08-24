package pl.rikitiki.im.xmpp.jingle;

import android.annotation.SuppressLint;
import android.util.Log;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import pl.rikitiki.im.Config;
import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.entities.Message;
import pl.rikitiki.im.entities.Transferable;
import pl.rikitiki.im.services.AbstractConnectionManager;
import pl.rikitiki.im.services.XmppConnectionService;
import pl.rikitiki.im.xml.Namespace;
import pl.rikitiki.im.xml.Element;
import pl.rikitiki.im.xmpp.OnIqPacketReceived;
import pl.rikitiki.im.xmpp.jid.Jid;
import pl.rikitiki.im.xmpp.jingle.stanzas.Content;
import pl.rikitiki.im.xmpp.jingle.stanzas.JinglePacket;
import pl.rikitiki.im.xmpp.stanzas.IqPacket;
import pl.rikitiki.im.xmpp.stanzas.MessagePacket;

public class JingleConnectionManager extends AbstractConnectionManager {
	private List<JingleConnection> connections = new CopyOnWriteArrayList<>();
	private List<JingleRtpConnection> rtpConnections = new CopyOnWriteArrayList<>();

	private HashMap<Jid, JingleCandidate> primaryCandidates = new HashMap<>();

	@SuppressLint("TrulyRandom")
	private SecureRandom random = new SecureRandom();

	public JingleConnectionManager(XmppConnectionService service) {
		super(service);
	}

	public void deliverPacket(Account account, JinglePacket packet) {
		if (packet.isAction("session-initiate")) {
			final Content content = packet.getJingleContent();
			if (content != null && content.hasRtpDescription()) {
				final JingleRtpConnection existing = findRtpConnection(account, packet.getSessionId());
				if (existing != null) {
					// We already have a PROPOSED connection from an earlier
					// XEP-0353 <propose/>; this is the real session-initiate
					// that follows our <proceed/>, not a fresh call.
					existing.receiveSessionInitiate(packet);
					return;
				}
				JingleRtpConnection connection = new JingleRtpConnection(this);
				connection.init(account, packet);
				rtpConnections.add(connection);
			} else {
				JingleConnection connection = new JingleConnection(this);
				connection.init(account, packet);
				connections.add(connection);
			}
		} else {
			for (JingleSession session : allSessions()) {
				if (session.getAccount() == account
						&& session.getSessionId().equals(
								packet.getSessionId())
						&& session.getCounterPart().equals(packet.getFrom())) {
					session.deliverPacket(packet);
					return;
				}
			}
			IqPacket response = packet.generateResponse(IqPacket.TYPE.ERROR);
			Element error = response.addChild("error");
			error.setAttribute("type", "cancel");
			error.addChild("item-not-found",
					"urn:ietf:params:xml:ns:xmpp-stanzas");
			error.addChild("unknown-session", "urn:xmpp:jingle:errors:1");
			account.getXmppConnection().sendIqPacket(response, null);
		}
	}

	private List<JingleSession> allSessions() {
		final List<JingleSession> all = new ArrayList<>(connections.size() + rtpConnections.size());
		all.addAll(connections);
		all.addAll(rtpConnections);
		return all;
	}

	public JingleRtpConnection createOutgoingRtpConnection(final Account account, final Jid counterpart) {
		return createOutgoingRtpConnection(account, counterpart, false);
	}

	public JingleRtpConnection createOutgoingRtpConnection(final Account account, final Jid counterpart, final boolean isVideo) {
		final JingleRtpConnection connection = new JingleRtpConnection(this);
		rtpConnections.add(connection);
		connection.init(account, counterpart, isVideo);
		return connection;
	}

	public void finishRtpConnection(final JingleRtpConnection connection) {
		this.rtpConnections.remove(connection);
	}

	/**
	 * The single currently active call, if any. Only one call is supported
	 * at a time, so callers (notification actions, in-call UI) don't need
	 * to look one up by session id.
	 */
	public JingleRtpConnection getRtpConnection() {
		return rtpConnections.isEmpty() ? null : rtpConnections.get(0);
	}

	private JingleRtpConnection findRtpConnection(final Account account, final String sessionId) {
		if (sessionId == null) {
			return null;
		}
		for (final JingleRtpConnection connection : rtpConnections) {
			if (connection.getAccount() == account && sessionId.equals(connection.getSessionId())) {
				return connection;
			}
		}
		return null;
	}

	/**
	 * Handles the XEP-0353 (Jingle Message Initiation) <propose/>, <proceed/>,
	 * <reject/> and <retract/> messages that precede/replace the raw Jingle
	 * IQ handshake for calls, so a session-initiate can be routed to a
	 * specific device rather than a bare JID the server can't deliver an IQ to.
	 */
	public void deliverMessage(final Account account, final MessagePacket packet) {
		final Element propose = packet.findChild("propose", Namespace.JINGLE_MESSAGE);
		final Element proceed = packet.findChild("proceed", Namespace.JINGLE_MESSAGE);
		final Element reject = packet.findChild("reject", Namespace.JINGLE_MESSAGE);
		final Element retract = packet.findChild("retract", Namespace.JINGLE_MESSAGE);
		if (propose != null) {
			boolean isVideo = false;
			for (final Element description : propose.getChildren()) {
				if ("description".equals(description.getName()) && "video".equals(description.getAttribute("media"))) {
					isVideo = true;
					break;
				}
			}
			final JingleRtpConnection connection = new JingleRtpConnection(this);
			connection.initIncomingProposal(account, packet, propose.getAttribute("id"), isVideo);
			rtpConnections.add(connection);
		} else if (proceed != null) {
			final JingleRtpConnection connection = findRtpConnection(account, proceed.getAttribute("id"));
			if (connection != null) {
				connection.receiveProceed(packet);
			}
		} else if (reject != null) {
			final JingleRtpConnection connection = findRtpConnection(account, reject.getAttribute("id"));
			if (connection != null) {
				connection.receiveReject();
			}
		} else if (retract != null) {
			final JingleRtpConnection connection = findRtpConnection(account, retract.getAttribute("id"));
			if (connection != null) {
				connection.receiveRetract();
			}
		}
	}

	public JingleConnection createNewConnection(Message message) {
		Transferable old = message.getTransferable();
		if (old != null) {
			old.cancel();
		}
		JingleConnection connection = new JingleConnection(this);
		mXmppConnectionService.markMessage(message,Message.STATUS_WAITING);
		connection.init(message);
		this.connections.add(connection);
		return connection;
	}

	public JingleConnection createNewConnection(final JinglePacket packet) {
		JingleConnection connection = new JingleConnection(this);
		this.connections.add(connection);
		return connection;
	}

	public void finishConnection(JingleConnection connection) {
		this.connections.remove(connection);
	}

	public void getPrimaryCandidate(Account account,
			final OnPrimaryCandidateFound listener) {
		if (Config.DISABLE_PROXY_LOOKUP) {
			listener.onPrimaryCandidateFound(false, null);
			return;
		}
		if (!this.primaryCandidates.containsKey(account.getJid().toBareJid())) {
			final Jid proxy = account.getXmppConnection().findDiscoItemByFeature(Namespace.BYTE_STREAMS);
			if (proxy != null) {
				IqPacket iq = new IqPacket(IqPacket.TYPE.GET);
				iq.setTo(proxy);
				iq.query(Namespace.BYTE_STREAMS);
				account.getXmppConnection().sendIqPacket(iq,new OnIqPacketReceived() {

					@Override
					public void onIqPacketReceived(Account account, IqPacket packet) {
						Element streamhost = packet.query().findChild("streamhost", Namespace.BYTE_STREAMS);
						final String host = streamhost == null ? null : streamhost.getAttribute("host");
						final String port = streamhost == null ? null : streamhost.getAttribute("port");
						if (host != null && port != null) {
							try {
								JingleCandidate candidate = new JingleCandidate(nextRandomId(), true);
								candidate.setHost(host);
								candidate.setPort(Integer.parseInt(port));
								candidate.setType(JingleCandidate.TYPE_PROXY);
								candidate.setJid(proxy);
								candidate.setPriority(655360 + 65535);
								primaryCandidates.put(account.getJid().toBareJid(),candidate);
								listener.onPrimaryCandidateFound(true,candidate);
							} catch (final NumberFormatException e) {
								listener.onPrimaryCandidateFound(false,null);
								return;
							}
						} else {
							listener.onPrimaryCandidateFound(false,null);
						}
					}
				});
			} else {
				listener.onPrimaryCandidateFound(false, null);
			}

		} else {
			listener.onPrimaryCandidateFound(true,
					this.primaryCandidates.get(account.getJid().toBareJid()));
		}
	}

	public String nextRandomId() {
		return new BigInteger(50, random).toString(32);
	}

	public void deliverIbbPacket(Account account, IqPacket packet) {
		String sid = null;
		Element payload = null;
		if (packet.hasChild("open", "http://jabber.org/protocol/ibb")) {
			payload = packet.findChild("open", "http://jabber.org/protocol/ibb");
			sid = payload.getAttribute("sid");
		} else if (packet.hasChild("data", "http://jabber.org/protocol/ibb")) {
			payload = packet.findChild("data", "http://jabber.org/protocol/ibb");
			sid = payload.getAttribute("sid");
		} else if (packet.hasChild("close","http://jabber.org/protocol/ibb")) {
			payload = packet.findChild("close", "http://jabber.org/protocol/ibb");
			sid = payload.getAttribute("sid");
		}
		if (sid != null) {
			for (JingleConnection connection : connections) {
				if (connection.getAccount() == account
						&& connection.hasTransportId(sid)) {
					JingleTransport transport = connection.getTransport();
					if (transport instanceof JingleInbandTransport) {
						JingleInbandTransport inbandTransport = (JingleInbandTransport) transport;
						inbandTransport.deliverPayload(packet, payload);
						return;
					}
				}
			}
			Log.d(Config.LOGTAG,"couldn't deliver payload: " + payload.toString());
		} else {
			Log.d(Config.LOGTAG, "no sid found in incoming ibb packet");
		}
	}

	public void cancelInTransmission() {
		for (JingleConnection connection : this.connections) {
			if (connection.getJingleStatus() == JingleConnection.JINGLE_STATUS_TRANSMITTING) {
				connection.cancel();
			}
		}
	}
}
