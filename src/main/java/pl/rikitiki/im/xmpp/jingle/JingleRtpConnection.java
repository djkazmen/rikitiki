package pl.rikitiki.im.xmpp.jingle;

import android.content.Intent;
import android.util.Log;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

import pl.rikitiki.im.Config;
import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.services.XmppConnectionService;
import pl.rikitiki.im.xml.Element;
import pl.rikitiki.im.xml.Namespace;
import pl.rikitiki.im.xmpp.OnIqPacketReceived;
import pl.rikitiki.im.xmpp.jid.Jid;
import pl.rikitiki.im.xmpp.jingle.stanzas.Content;
import pl.rikitiki.im.xmpp.jingle.stanzas.JinglePacket;
import pl.rikitiki.im.xmpp.jingle.stanzas.Reason;
import pl.rikitiki.im.xmpp.stanzas.IqPacket;
import pl.rikitiki.im.xmpp.stanzas.MessagePacket;

/**
 * Signaling state machine for a Jingle audio call (XEP-0166/0167/0176/0320),
 * proposed/answered via XEP-0353 (Jingle Message Initiation) messages so a
 * call can be addressed to a bare JID and land on whichever device answers,
 * with the local media handled by WebRTC through {@link WebRtcWrapper}. This
 * class translates between Jingle/JMI actions and WebRTC's offer/answer/ICE
 * callbacks (via {@link SdpJingleTranslator}) but never touches org.webrtc.*
 * types directly beyond passing them through.
 */
public class JingleRtpConnection implements JingleSession {

	public enum State {
		PROPOSED,
		SESSION_INITIALIZED,
		SESSION_ACCEPTED,
		CONNECTING,
		CONNECTED,
		REJECTED,
		RETRACTED,
		TERMINATED
	}

	public interface OnCallStateChanged {
		void onCallStateChanged(State state);
	}

	// SDP m-line order, enforced by convention: WebRtcWrapper always adds the
	// audio track before the video track, so createOffer/createAnswer always
	// emit m=audio first, and JSEP requires the answer to preserve the
	// offer's m-line order — so both call legs agree on these without needing
	// to negotiate mid strings.
	private static final int MID_AUDIO = 0;
	private static final int MID_VIDEO = 1;

	private final JingleConnectionManager mJingleConnectionManager;
	private final XmppConnectionService mXmppConnectionService;
	private final WebRtcWrapper webRtcWrapper = new WebRtcWrapper();
	private final CallAudioManager callAudioManager = new CallAudioManager();

	private Account account;
	private Jid initiator;
	private Jid responder;
	private Jid counterpart;
	private String sessionId;
	private String contentCreator = "initiator";
	private String contentName;
	private String videoContentName;
	private boolean isVideoCall = false;

	private State state = State.PROPOSED;
	private OnCallStateChanged callback;

	// True once the real Jingle session-initiate IQ has been seen, either
	// directly (no JMI) or after a JMI propose/proceed exchange. Lets
	// accept() know whether to send a JMI <proceed/> first or go straight to
	// building the SDP answer.
	private boolean sessionInitiateReceived = false;

	// Queued while we haven't received the remote description yet (incoming
	// candidates can race the session-accept/offer processing). Each
	// candidate remembers which content (audio/video) it belongs to, since a
	// video call's transport-info can arrive for either media before the
	// remote description that would let us resolve that inline.
	private static final class PendingRemoteCandidate {
		final Element candidate;
		final int mid;

		PendingRemoteCandidate(final Element candidate, final int mid) {
			this.candidate = candidate;
			this.mid = mid;
		}
	}

	private final List<PendingRemoteCandidate> pendingRemoteCandidates = new ArrayList<>();
	private boolean remoteDescriptionSet = false;

	public JingleRtpConnection(final JingleConnectionManager jingleConnectionManager) {
		this.mJingleConnectionManager = jingleConnectionManager;
		this.mXmppConnectionService = jingleConnectionManager.getXmppConnectionService();
	}

	@Override
	public Account getAccount() {
		return this.account;
	}

	@Override
	public String getSessionId() {
		return this.sessionId;
	}

	@Override
	public Jid getCounterPart() {
		return this.counterpart;
	}

	public State getState() {
		return this.state;
	}

	public boolean isInitiator() {
		return this.initiator != null && this.initiator.equals(this.account.getJid());
	}

	public boolean isVideoCall() {
		return this.isVideoCall;
	}

	private int midForContentName(final String name) {
		return name != null && name.equals(videoContentName) ? MID_VIDEO : MID_AUDIO;
	}

	public void setOnCallStateChangedListener(final OnCallStateChanged callback) {
		this.callback = callback;
	}

	private void transition(final State state) {
		this.state = state;
		Log.d(Config.LOGTAG, "rtp session " + sessionId + " -> " + state);
		if (state == State.PROPOSED && !isInitiator()) {
			mXmppConnectionService.getNotificationService().startRinging();
		} else {
			mXmppConnectionService.getNotificationService().stopRinging();
		}
		if (state == State.CONNECTED) {
			final android.app.Notification notification = mXmppConnectionService.getNotificationService().showOngoingCallNotification(this);
			mXmppConnectionService.startCallForeground(notification, isVideoCall);
			callAudioManager.start(mXmppConnectionService, isVideoCall);
		} else if (state == State.TERMINATED || state == State.REJECTED || state == State.RETRACTED) {
			mXmppConnectionService.getNotificationService().cancelCallNotification();
			mXmppConnectionService.stopCallForeground();
			callAudioManager.stop();
		}
		if (this.callback != null) {
			this.callback.onCallStateChanged(state);
		}
	}

	private final WebRtcWrapper.Listener webRtcListener = new WebRtcWrapper.Listener() {
		@Override
		public void onIceCandidate(final IceCandidate candidate) {
			sendTransportInfo(candidate);
		}

		@Override
		public void onIceConnectionChange(final PeerConnection.IceConnectionState iceState) {
			if (iceState == PeerConnection.IceConnectionState.CONNECTED
					|| iceState == PeerConnection.IceConnectionState.COMPLETED) {
				if (state != State.CONNECTED) {
					transition(State.CONNECTED);
				}
			} else if (iceState == PeerConnection.IceConnectionState.FAILED) {
				Log.d(Config.LOGTAG, "rtp session " + sessionId + ": ice connection failed");
				terminate("connectivity-error");
			}
		}

		@Override
		public void onRemoteVideoTrack(final VideoTrack track) {
			Log.d(Config.LOGTAG, "rtp session " + sessionId + ": received remote video track");
			remoteVideoTrack = track;
		}
	};

	private VideoTrack remoteVideoTrack;

	public VideoTrack getRemoteVideoTrack() {
		return this.remoteVideoTrack;
	}

	public VideoTrack getLocalVideoTrack() {
		return webRtcWrapper.getLocalVideoTrack();
	}

	/**
	 * Closes the WebRTC layer and drops our reference to the remote video
	 * track. Disposing the peer connection invalidates any VideoTrack
	 * objects tied to it — without clearing this field, a UI callback
	 * racing the close (or firing right after it) could call addSink() on
	 * an already-disposed track and crash with
	 * "IllegalStateException: MediaStreamTrack has been disposed."
	 */
	private void closeWebRtcWrapper() {
		webRtcWrapper.close();
		this.remoteVideoTrack = null;
	}

	public void switchCamera() {
		webRtcWrapper.switchCamera();
	}

	public java.util.List<CallAudioManager.RouteOption> getAvailableAudioRoutes() {
		return callAudioManager.getAvailableRoutes(mXmppConnectionService);
	}

	public void setAudioRoute(final CallAudioManager.Route route) {
		callAudioManager.setRoute(route);
	}

	public CallAudioManager.Route getCurrentAudioRoute() {
		return callAudioManager.getCurrentRoute();
	}

	/**
	 * Outgoing call: the local user is calling `counterpart` (typically a
	 * bare JID; we don't know which of their devices will pick up yet).
	 * Sends a XEP-0353 <propose/> first rather than a raw Jingle IQ, which
	 * the server can't route to a specific device on its own.
	 */
	public void init(final Account account, final Jid counterpart) {
		init(account, counterpart, false);
	}

	public void init(final Account account, final Jid counterpart, final boolean isVideo) {
		this.account = account;
		this.counterpart = counterpart;
		this.initiator = account.getJid();
		this.responder = counterpart;
		this.sessionId = this.mJingleConnectionManager.nextRandomId();
		this.contentName = this.mJingleConnectionManager.nextRandomId();
		this.isVideoCall = isVideo;
		if (isVideo) {
			this.videoContentName = this.mJingleConnectionManager.nextRandomId();
		}
		transition(State.PROPOSED);
		final MessagePacket propose = new MessagePacket();
		propose.setType(MessagePacket.TYPE_CHAT);
		propose.setTo(counterpart);
		final Element proposeEl = propose.addChild("propose", Namespace.JINGLE_MESSAGE);
		proposeEl.setAttribute("id", sessionId);
		proposeEl.addChild("description", Content.RTP_NS).setAttribute("media", "audio");
		if (isVideo) {
			proposeEl.addChild("description", Content.RTP_NS).setAttribute("media", "video");
		}
		mXmppConnectionService.sendMessagePacket(account, propose);
	}

	/**
	 * The callee's device answered our proposal; now that we know their
	 * exact resource, start the real Jingle session with them.
	 */
	public void receiveProceed(final MessagePacket packet) {
		if (state != State.PROPOSED) {
			Log.d(Config.LOGTAG, "received jmi proceed in unexpected state " + state);
			return;
		}
		this.counterpart = packet.getFrom();
		transition(State.SESSION_INITIALIZED);
		fetchIceServersThen(new Runnable() {
			@Override
			public void run() {
				webRtcWrapper.createOffer(new WebRtcWrapper.SdpCallback() {
					@Override
					public void onSdpCreated(final SessionDescription sdp) {
						sendSessionInitiate(sdp);
					}

					@Override
					public void onSdpError(final String error) {
						Log.d(Config.LOGTAG, "rtp session " + sessionId + ": failed to create offer: " + error);
						transition(State.TERMINATED);
						mJingleConnectionManager.finishRtpConnection(JingleRtpConnection.this);
					}
				});
			}
		});
	}

	/** The callee declined before/without ringing on any device. */
	public void receiveReject() {
		transition(State.REJECTED);
		closeWebRtcWrapper();
		mJingleConnectionManager.finishRtpConnection(this);
	}

	/** The caller withdrew the proposal before we (the callee) answered. */
	public void receiveRetract() {
		transition(State.RETRACTED);
		closeWebRtcWrapper();
		mJingleConnectionManager.finishRtpConnection(this);
	}

	private void sendSessionInitiate(final SessionDescription sdp) {
		final JinglePacket packet = bootstrapPacket("session-initiate");
		if (isVideoCall) {
			final List<Content> contents = new ArrayList<>();
			contents.add(new Content(this.contentCreator, this.contentName));
			contents.add(new Content(this.contentCreator, this.videoContentName));
			SdpJingleTranslator.fillContentsFromSdp(contents, sdp);
			for (final Content content : contents) {
				packet.addContent(content);
			}
		} else {
			final Content content = new Content(this.contentCreator, this.contentName);
			SdpJingleTranslator.fillContentFromSdp(content, sdp);
			packet.setContent(content);
		}
		sendJinglePacket(packet, new OnIqPacketReceived() {
			@Override
			public void onIqPacketReceived(final Account account, final IqPacket response) {
				if (response.getType() != IqPacket.TYPE.RESULT) {
					Log.d(Config.LOGTAG, account.getJid().toBareJid() + ": rtp session-initiate was not acknowledged: " + response);
					transition(State.TERMINATED);
					mJingleConnectionManager.finishRtpConnection(JingleRtpConnection.this);
				}
			}
		});
	}

	/**
	 * Incoming call via a direct Jingle session-initiate IQ, with no
	 * preceding XEP-0353 proposal (older/non-JMI caller). `packet` is the
	 * session-initiate we just received.
	 */
	public void init(final Account account, final JinglePacket packet) {
		this.account = account;
		this.sessionId = packet.getSessionId();
		this.initiator = packet.getFrom();
		this.responder = account.getJid();
		this.counterpart = packet.getFrom();
		this.sessionInitiateReceived = true;
		applyIncomingSessionInitiate(packet);
		transition(State.PROPOSED);
		mXmppConnectionService.getNotificationService().showIncomingCallNotification(this);
		launchRtpSessionActivity();
	}

	/**
	 * Incoming call, first stage: a XEP-0353 <propose/> just arrived. No
	 * WebRTC setup yet — that happens once the user accepts and the real
	 * session-initiate follows.
	 */
	public void initIncomingProposal(final Account account, final MessagePacket packet, final String sessionId, final boolean isVideo) {
		this.account = account;
		this.sessionId = sessionId;
		this.counterpart = packet.getFrom();
		this.initiator = this.counterpart;
		this.responder = account.getJid();
		this.sessionInitiateReceived = false;
		this.isVideoCall = isVideo;
		transition(State.PROPOSED);
		mXmppConnectionService.getNotificationService().showIncomingCallNotification(this);
		launchRtpSessionActivity();
	}

	/**
	 * Incoming call, second stage: the real session-initiate IQ that follows
	 * our XEP-0353 <proceed/>, routed here (instead of creating a new
	 * connection) because the session id matches our PROPOSED one.
	 */
	public void receiveSessionInitiate(final JinglePacket packet) {
		this.sessionInitiateReceived = true;
		applyIncomingSessionInitiate(packet);
	}

	private void applyIncomingSessionInitiate(final JinglePacket packet) {
		final boolean autoAccept = this.state == State.PROPOSED && this.sessionInitiateReceived;
		final List<Content> incomingContents = packet.getContents();
		// The session-initiate is authoritative on whether this is a video
		// call, regardless of what an earlier JMI <propose/> claimed.
		this.isVideoCall = incomingContents.size() > 1;
		final Content firstContent = incomingContents.get(0);
		this.contentCreator = firstContent.getAttribute("creator");
		this.contentName = firstContent.getAttribute("name");
		this.videoContentName = isVideoCall ? incomingContents.get(1).getAttribute("name") : null;
		mXmppConnectionService.sendIqPacket(account, packet.generateResponse(IqPacket.TYPE.RESULT), null);
		final SessionDescription offer = isVideoCall
				? SdpJingleTranslator.contentsToSdp(incomingContents, SessionDescription.Type.OFFER)
				: SdpJingleTranslator.contentToSdp(firstContent, SessionDescription.Type.OFFER);
		fetchIceServersThen(new Runnable() {
			@Override
			public void run() {
				webRtcWrapper.setRemoteDescription(offer, new Runnable() {
					@Override
					public void run() {
						remoteDescriptionSet = true;
						drainPendingRemoteCandidates();
						if (autoAccept) {
							// We already told the caller we're picking up
							// (via JMI <proceed/>) before this IQ arrived.
							doCreateAnswerAndSendAccept();
						}
					}
				}, new WebRtcWrapper.OnError() {
					@Override
					public void onError(final String error) {
						Log.d(Config.LOGTAG, "rtp session " + sessionId + ": failed to set remote offer: " + error);
					}
				});
			}
		});
	}

	private void fetchIceServersThen(final Runnable next) {
		mXmppConnectionService.requestExternalServices(account, new XmppConnectionService.OnExternalServicesReceived() {
			@Override
			public void onExternalServicesReceived(final List<ExternalService> services) {
				webRtcWrapper.setup(mXmppConnectionService, toIceServers(services), webRtcListener, isVideoCall);
				next.run();
			}
		});
	}

	private List<PeerConnection.IceServer> toIceServers(final List<ExternalService> services) {
		final List<PeerConnection.IceServer> iceServers = new ArrayList<>();
		for (final ExternalService service : services) {
			final String type = service.getType();
			final String scheme;
			if ("stun".equals(type) || "stuns".equals(type)) {
				scheme = "stun";
			} else if ("turn".equals(type)) {
				scheme = "turn";
			} else if ("turns".equals(type)) {
				scheme = "turns";
			} else {
				continue;
			}
			final String url = scheme + ":" + service.getHost() + ":" + service.getPort();
			final PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(url);
			if (service.getUsername() != null) {
				builder.setUsername(service.getUsername());
			}
			if (service.getPassword() != null) {
				builder.setPassword(service.getPassword());
			}
			iceServers.add(builder.createIceServer());
		}
		return iceServers;
	}

	@Override
	public void deliverPacket(final JinglePacket packet) {
		boolean returnResult = true;
		if (packet.isAction("session-accept")) {
			returnResult = receiveSessionAccept(packet);
		} else if (packet.isAction("session-terminate")) {
			receiveSessionTerminate(packet);
		} else if (packet.isAction("transport-info")) {
			receiveTransportInfo(packet);
		} else {
			Log.d(Config.LOGTAG, "rtp connection received unsupported action: " + packet.getAction());
			returnResult = false;
		}
		final IqPacket response = packet.generateResponse(returnResult ? IqPacket.TYPE.RESULT : IqPacket.TYPE.ERROR);
		mXmppConnectionService.sendIqPacket(account, response, null);
	}

	private boolean receiveSessionAccept(final JinglePacket packet) {
		if (state != State.SESSION_INITIALIZED) {
			Log.d(Config.LOGTAG, "received session-accept in unexpected state " + state);
			return false;
		}
		transition(State.SESSION_ACCEPTED);
		final List<Content> acceptedContents = packet.getContents();
		final SessionDescription answer;
		if (isVideoCall && acceptedContents.size() > 1) {
			answer = SdpJingleTranslator.contentsToSdp(acceptedContents, SessionDescription.Type.ANSWER);
		} else if (isVideoCall) {
			// The callee's accept only carried one content — likely a
			// pre-video client that doesn't understand the second (video)
			// content and silently dropped it. Our local offer already
			// committed two m-lines to WebRTC, so fall back to an
			// audio-only call rather than failing the whole connection.
			Log.d(Config.LOGTAG, "rtp session " + sessionId + ": callee accepted without video, falling back to audio-only");
			answer = SdpJingleTranslator.contentsToSdpWithRejectedVideo(acceptedContents.get(0), SessionDescription.Type.ANSWER);
			webRtcWrapper.disableLocalVideo();
			this.isVideoCall = false;
			if (this.callback != null) {
				this.callback.onCallStateChanged(state);
			}
		} else {
			answer = SdpJingleTranslator.contentToSdp(packet.getJingleContent(), SessionDescription.Type.ANSWER);
		}
		webRtcWrapper.setRemoteDescription(answer, new Runnable() {
			@Override
			public void run() {
				remoteDescriptionSet = true;
				drainPendingRemoteCandidates();
			}
		}, new WebRtcWrapper.OnError() {
			@Override
			public void onError(final String error) {
				Log.d(Config.LOGTAG, "rtp session " + sessionId + ": failed to set remote answer: " + error);
			}
		});
		return true;
	}

	private void receiveTransportInfo(final JinglePacket packet) {
		for (final Content content : packet.getContents()) {
			if (!content.hasIceUdpTransport()) {
				continue;
			}
			final int mid = midForContentName(content.getAttribute("name"));
			for (final Element candidate : content.iceUdpTransport().getChildren()) {
				if ("candidate".equals(candidate.getName())) {
					if (remoteDescriptionSet) {
						webRtcWrapper.addIceCandidate(SdpJingleTranslator.jingleCandidateToIce(candidate, String.valueOf(mid), mid));
					} else {
						pendingRemoteCandidates.add(new PendingRemoteCandidate(candidate, mid));
					}
				}
			}
		}
	}

	private void drainPendingRemoteCandidates() {
		for (final PendingRemoteCandidate pending : pendingRemoteCandidates) {
			webRtcWrapper.addIceCandidate(SdpJingleTranslator.jingleCandidateToIce(pending.candidate, String.valueOf(pending.mid), pending.mid));
		}
		pendingRemoteCandidates.clear();
	}

	private void sendTransportInfo(final IceCandidate iceCandidate) {
		if (state == State.TERMINATED || state == State.REJECTED || state == State.RETRACTED) {
			return;
		}
		final JinglePacket packet = bootstrapPacket("transport-info");
		final boolean forVideo = iceCandidate.sdpMLineIndex == MID_VIDEO && videoContentName != null;
		final Content content = new Content(this.contentCreator, forVideo ? this.videoContentName : this.contentName);
		content.iceUdpTransport().addChild(
				SdpJingleTranslator.iceCandidateToJingle(iceCandidate, mJingleConnectionManager.nextRandomId()));
		packet.setContent(content);
		sendJinglePacket(packet);
	}

	private void receiveSessionTerminate(final JinglePacket packet) {
		Log.d(Config.LOGTAG, "DEBUGRTP: received session-terminate: " + packet.toString());
		final Reason reason = packet.getReason();
		if (reason != null && reason.hasChild("decline")) {
			transition(State.REJECTED);
		} else {
			transition(State.TERMINATED);
		}
		closeWebRtcWrapper();
		mJingleConnectionManager.finishRtpConnection(this);
	}

	/**
	 * Callee accepts an incoming (PROPOSED) call. If we only have a JMI
	 * proposal so far, tells the caller we're picking up and waits for the
	 * real session-initiate; if the real offer already arrived, answers it
	 * directly.
	 */
	public void accept() {
		if (state != State.PROPOSED) {
			Log.d(Config.LOGTAG, "tried to accept rtp session in state " + state);
			return;
		}
		if (!sessionInitiateReceived) {
			final MessagePacket proceed = new MessagePacket();
			proceed.setType(MessagePacket.TYPE_CHAT);
			proceed.setTo(counterpart);
			proceed.addChild("proceed", Namespace.JINGLE_MESSAGE).setAttribute("id", sessionId);
			mXmppConnectionService.sendMessagePacket(account, proceed);
			return;
		}
		doCreateAnswerAndSendAccept();
	}

	private void doCreateAnswerAndSendAccept() {
		webRtcWrapper.createAnswer(new WebRtcWrapper.SdpCallback() {
			@Override
			public void onSdpCreated(final SessionDescription sdp) {
				final JinglePacket packet = bootstrapPacket("session-accept");
				if (isVideoCall) {
					final List<Content> contents = new ArrayList<>();
					contents.add(new Content(contentCreator, contentName));
					contents.add(new Content(contentCreator, videoContentName));
					SdpJingleTranslator.fillContentsFromSdp(contents, sdp);
					for (final Content content : contents) {
						packet.addContent(content);
					}
				} else {
					final Content content = new Content(contentCreator, contentName);
					SdpJingleTranslator.fillContentFromSdp(content, sdp);
					packet.setContent(content);
				}
				transition(State.SESSION_ACCEPTED);
				sendJinglePacket(packet);
			}

			@Override
			public void onSdpError(final String error) {
				Log.d(Config.LOGTAG, "rtp session " + sessionId + ": failed to create answer: " + error);
				terminate("failed-application");
			}
		});
	}

	/**
	 * Ends the call from our side: decline/cancel while still only
	 * PROPOSED (sent as a JMI message, since no real Jingle session exists
	 * on the wire yet), or hangup/cancel after the real session started
	 * (sent as a Jingle session-terminate IQ).
	 */
	public void terminate(final String reasonName) {
		if (state == State.PROPOSED && !sessionInitiateReceived) {
			final MessagePacket packet = new MessagePacket();
			packet.setType(MessagePacket.TYPE_CHAT);
			packet.setTo(counterpart);
			packet.addChild(isInitiator() ? "retract" : "reject", Namespace.JINGLE_MESSAGE).setAttribute("id", sessionId);
			mXmppConnectionService.sendMessagePacket(account, packet);
		} else {
			final JinglePacket packet = bootstrapPacket("session-terminate");
			final Reason reason = new Reason();
			reason.addChild(reasonName);
			packet.setReason(reason);
			sendJinglePacket(packet);
		}
		// transition() before closing the WebRTC layer: it fires the UI
		// callback synchronously, and RtpSessionActivity re-attaches video
		// sinks on every state change — closing first would leave it
		// holding a disposed (but non-null) VideoTrack reference and crash.
		transition(isInitiator() ? State.RETRACTED : State.REJECTED);
		closeWebRtcWrapper();
		mJingleConnectionManager.finishRtpConnection(this);
	}

	private void launchRtpSessionActivity() {
		final Intent intent = new Intent(mXmppConnectionService, pl.rikitiki.im.ui.RtpSessionActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		mXmppConnectionService.startActivity(intent);
	}

	private JinglePacket bootstrapPacket(final String action) {
		final JinglePacket packet = new JinglePacket();
		packet.setAction(action);
		packet.setFrom(account.getJid());
		packet.setTo(counterpart);
		packet.setSessionId(sessionId);
		packet.setInitiator(initiator);
		return packet;
	}

	private void sendJinglePacket(final JinglePacket packet) {
		mXmppConnectionService.sendIqPacket(account, packet, null);
	}

	private void sendJinglePacket(final JinglePacket packet, final OnIqPacketReceived callback) {
		mXmppConnectionService.sendIqPacket(account, packet, callback);
	}
}
