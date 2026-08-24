package pl.rikitiki.im.xmpp.jingle;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.Collections;
import java.util.List;

import pl.rikitiki.im.Config;

/**
 * Owns the WebRTC PeerConnectionFactory/PeerConnection lifecycle for one
 * call. All direct org.webrtc.* usage is isolated here; {@link
 * JingleRtpConnection} only deals in Jingle/XMPP terms and the small
 * callback interfaces below.
 */
public class WebRtcWrapper {

	private static PeerConnectionFactory factory;
	private static EglBase eglBase;

	private PeerConnection peerConnection;
	private AudioTrack localAudioTrack;
	private VideoCapturer videoCapturer;
	private SurfaceTextureHelper surfaceTextureHelper;
	private VideoSource videoSource;
	private VideoTrack localVideoTrack;
	private Listener listener;

	/**
	 * Independent of any single call's {@link #setup}: RtpSessionActivity
	 * needs an EglBase.Context to init its renderers before (or even
	 * without) a call being set up yet. Lives for the process lifetime,
	 * same as the PeerConnectionFactory, and is intentionally never
	 * released in {@link #close()}.
	 */
	public static synchronized EglBase.Context ensureEglBase(final Context context) {
		if (eglBase == null) {
			eglBase = EglBase.create();
		}
		return eglBase.getEglBaseContext();
	}

	private static synchronized PeerConnectionFactory getOrCreateFactory(final Context context) {
		if (factory == null) {
			final EglBase.Context eglContext = ensureEglBase(context);
			PeerConnectionFactory.initialize(
					PeerConnectionFactory.InitializationOptions.builder(context.getApplicationContext())
							.createInitializationOptions());
			factory = PeerConnectionFactory.builder()
					.setAudioDeviceModule(JavaAudioDeviceModule.builder(context.getApplicationContext())
							.createAudioDeviceModule())
					.setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglContext, true, true))
					.setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglContext))
					.createPeerConnectionFactory();
		}
		return factory;
	}

	public void setup(final Context context, final List<PeerConnection.IceServer> iceServers, final Listener listener, final boolean isVideoCall) {
		this.listener = listener;
		final PeerConnectionFactory pcf = getOrCreateFactory(context);
		final PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
		rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
		this.peerConnection = pcf.createPeerConnection(rtcConfig, observer);
		if (this.peerConnection == null) {
			Log.d(Config.LOGTAG, "WebRtcWrapper: createPeerConnection returned null");
			return;
		}
		final AudioSource audioSource = pcf.createAudioSource(new MediaConstraints());
		this.localAudioTrack = pcf.createAudioTrack("rikitiki-audio0", audioSource);
		// Audio track must be added before the video track: WebRTC's own
		// createOffer()/createAnswer() emit m-lines in track-add order, and
		// SdpJingleTranslator/JingleRtpConnection assume mid0=audio, mid1=video.
		this.peerConnection.addTrack(this.localAudioTrack, Collections.singletonList("rikitiki-stream0"));
		if (isVideoCall) {
			setupVideoCapture(context, pcf);
		}
	}

	private void setupVideoCapture(final Context context, final PeerConnectionFactory pcf) {
		final CameraEnumerator enumerator = Camera2Enumerator.isSupported(context)
				? new Camera2Enumerator(context)
				: new Camera1Enumerator();
		String deviceName = null;
		for (final String name : enumerator.getDeviceNames()) {
			if (enumerator.isFrontFacing(name)) {
				deviceName = name;
				break;
			}
		}
		if (deviceName == null) {
			final String[] names = enumerator.getDeviceNames();
			deviceName = names.length > 0 ? names[0] : null;
		}
		if (deviceName == null) {
			Log.d(Config.LOGTAG, "WebRtcWrapper: no camera available");
			return;
		}
		this.videoCapturer = enumerator.createCapturer(deviceName, null);
		if (this.videoCapturer == null) {
			Log.d(Config.LOGTAG, "WebRtcWrapper: createCapturer returned null");
			return;
		}
		this.surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCameraThread", ensureEglBase(context));
		this.videoSource = pcf.createVideoSource(this.videoCapturer.isScreencast());
		this.videoCapturer.initialize(this.surfaceTextureHelper, context.getApplicationContext(), this.videoSource.getCapturerObserver());
		this.videoCapturer.startCapture(1280, 720, 30);
		this.localVideoTrack = pcf.createVideoTrack("rikitiki-video0", this.videoSource);
		this.peerConnection.addTrack(this.localVideoTrack, Collections.singletonList("rikitiki-stream0"));
	}

	public VideoTrack getLocalVideoTrack() {
		return this.localVideoTrack;
	}

	public void switchCamera() {
		if (videoCapturer instanceof CameraVideoCapturer) {
			((CameraVideoCapturer) videoCapturer).switchCamera(null);
		}
	}

	/**
	 * Tears down local video capture/sending mid-call, without touching the
	 * audio track or the peer connection itself. Used when a callee's
	 * session-accept turns out not to include video (e.g. a pre-video
	 * client) — there's no point continuing to capture and encode video
	 * frames nobody asked for.
	 */
	public void disableLocalVideo() {
		if (peerConnection != null && localVideoTrack != null) {
			for (final RtpSender sender : peerConnection.getSenders()) {
				if (sender.track() == localVideoTrack) {
					peerConnection.removeTrack(sender);
					break;
				}
			}
		}
		if (videoCapturer != null) {
			try {
				videoCapturer.stopCapture();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			videoCapturer.dispose();
			videoCapturer = null;
		}
		if (localVideoTrack != null) {
			localVideoTrack.dispose();
			localVideoTrack = null;
		}
		if (videoSource != null) {
			videoSource.dispose();
			videoSource = null;
		}
		if (surfaceTextureHelper != null) {
			surfaceTextureHelper.dispose();
			surfaceTextureHelper = null;
		}
	}

	public void createOffer(final SdpCallback callback) {
		if (peerConnection == null) {
			callback.onSdpError("no peer connection");
			return;
		}
		peerConnection.createOffer(new SimpleSdpObserver() {
			@Override
			public void onCreateSuccess(final SessionDescription sdp) {
				setLocalDescriptionThen(sdp, callback);
			}

			@Override
			public void onCreateFailure(final String error) {
				callback.onSdpError(error);
			}
		}, new MediaConstraints());
	}

	public void createAnswer(final SdpCallback callback) {
		if (peerConnection == null) {
			callback.onSdpError("no peer connection");
			return;
		}
		peerConnection.createAnswer(new SimpleSdpObserver() {
			@Override
			public void onCreateSuccess(final SessionDescription sdp) {
				setLocalDescriptionThen(sdp, callback);
			}

			@Override
			public void onCreateFailure(final String error) {
				callback.onSdpError(error);
			}
		}, new MediaConstraints());
	}

	private void setLocalDescriptionThen(final SessionDescription sdp, final SdpCallback callback) {
		peerConnection.setLocalDescription(new SimpleSdpObserver() {
			@Override
			public void onSetSuccess() {
				callback.onSdpCreated(sdp);
			}

			@Override
			public void onSetFailure(final String error) {
				callback.onSdpError(error);
			}
		}, sdp);
	}

	public void setRemoteDescription(final SessionDescription sdp, final Runnable onSuccess, final OnError onError) {
		if (peerConnection == null) {
			onError.onError("no peer connection");
			return;
		}
		peerConnection.setRemoteDescription(new SimpleSdpObserver() {
			@Override
			public void onSetSuccess() {
				onSuccess.run();
			}

			@Override
			public void onSetFailure(final String error) {
				onError.onError(error);
			}
		}, sdp);
	}

	public void addIceCandidate(final IceCandidate candidate) {
		if (peerConnection != null) {
			peerConnection.addIceCandidate(candidate);
		}
	}

	public void close() {
		if (peerConnection != null) {
			peerConnection.close();
			peerConnection.dispose();
			peerConnection = null;
		}
		if (videoCapturer != null) {
			try {
				videoCapturer.stopCapture();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			videoCapturer.dispose();
			videoCapturer = null;
		}
		if (localVideoTrack != null) {
			localVideoTrack.dispose();
			localVideoTrack = null;
		}
		if (videoSource != null) {
			videoSource.dispose();
			videoSource = null;
		}
		if (surfaceTextureHelper != null) {
			surfaceTextureHelper.dispose();
			surfaceTextureHelper = null;
		}
		if (localAudioTrack != null) {
			localAudioTrack.dispose();
			localAudioTrack = null;
		}
	}

	private final PeerConnection.Observer observer = new PeerConnection.Observer() {
		@Override
		public void onSignalingChange(final PeerConnection.SignalingState signalingState) {
		}

		@Override
		public void onIceConnectionChange(final PeerConnection.IceConnectionState state) {
			Log.d(Config.LOGTAG, "WebRtcWrapper: ice connection state=" + state);
			if (listener != null) {
				listener.onIceConnectionChange(state);
			}
		}

		@Override
		public void onIceConnectionReceivingChange(final boolean receiving) {
		}

		@Override
		public void onIceGatheringChange(final PeerConnection.IceGatheringState iceGatheringState) {
		}

		@Override
		public void onIceCandidate(final IceCandidate iceCandidate) {
			if (listener != null) {
				listener.onIceCandidate(iceCandidate);
			}
		}

		@Override
		public void onIceCandidatesRemoved(final IceCandidate[] iceCandidates) {
		}

		@Override
		public void onAddStream(final MediaStream mediaStream) {
		}

		@Override
		public void onRemoveStream(final MediaStream mediaStream) {
		}

		@Override
		public void onDataChannel(final DataChannel dataChannel) {
		}

		@Override
		public void onRenegotiationNeeded() {
		}

		@Override
		public void onAddTrack(final RtpReceiver rtpReceiver, final MediaStream[] mediaStreams) {
			final MediaStreamTrack track = rtpReceiver.track();
			if (listener != null && track instanceof VideoTrack) {
				listener.onRemoteVideoTrack((VideoTrack) track);
			}
		}
	};

	private static class SimpleSdpObserver implements SdpObserver {
		@Override
		public void onCreateSuccess(final SessionDescription sessionDescription) {
		}

		@Override
		public void onSetSuccess() {
		}

		@Override
		public void onCreateFailure(final String s) {
		}

		@Override
		public void onSetFailure(final String s) {
		}
	}

	public interface SdpCallback {
		void onSdpCreated(SessionDescription sdp);

		void onSdpError(String error);
	}

	public interface OnError {
		void onError(String error);
	}

	public interface Listener {
		void onIceCandidate(IceCandidate candidate);

		void onIceConnectionChange(PeerConnection.IceConnectionState state);

		void onRemoteVideoTrack(VideoTrack track);
	}
}
