package pl.rikitiki.im.xmpp.jingle;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * WebRTC's JavaAudioDeviceModule (see WebRtcWrapper) only handles audio I/O
 * — it does not put the device into call mode or pick an audio route, which
 * every real WebRTC integration is expected to manage itself (this is the
 * same thing Google's own AppRTCAudioManager reference sample does).
 * Without this, a call's audio is routed however Android's default media
 * behavior happens to land, which for a video call usually means the
 * earpiece — unusable, since you're holding the phone up to look at it.
 *
 * Bluetooth needs its own handling beyond "just don't override it" (unlike
 * a wired headset, which the OS routes to automatically once speakerphone
 * is off): a Bluetooth headset's mic+speaker only carry call audio once the
 * SCO (Synchronous Connection-Oriented) link is explicitly started, so
 * picking BLUETOOTH as a route means calling startBluetoothSco() ourselves.
 * Device enumeration/naming here only needs TYPE_BLUETOOTH_SCO — A2DP is a
 * media-only profile with no microphone path, so it's not a usable call
 * route even though it's a "Bluetooth audio device" in the general sense.
 */
public class CallAudioManager {

	public enum Route {
		EARPIECE, SPEAKER, WIRED_HEADSET, BLUETOOTH
	}

	public static final class RouteOption {
		public final Route route;
		public final String label;

		RouteOption(final Route route, final String label) {
			this.route = route;
			this.label = label;
		}
	}

	private AudioManager audioManager;
	private AudioManager.OnAudioFocusChangeListener focusChangeListener;
	private AudioFocusRequest focusRequest;
	private int originalMode = AudioManager.MODE_NORMAL;
	private boolean originalSpeakerphoneOn = false;
	private boolean started = false;
	private Route currentRoute = Route.EARPIECE;

	public synchronized void start(final Context context, final boolean isVideoCall) {
		if (started) {
			return;
		}
		started = true;
		audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if (audioManager == null) {
			return;
		}
		originalMode = audioManager.getMode();
		originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
		audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
		requestAudioFocus();
		// An already-connected headset (wired or Bluetooth) takes priority
		// over the video-call-defaults-to-speaker heuristic below — if
		// you're already wearing one, that's almost certainly what you want
		// to keep using regardless of call type.
		if (getConnectedBluetoothDeviceName(context) != null) {
			setRoute(Route.BLUETOOTH);
		} else if (isWiredHeadsetConnected(context)) {
			setRoute(Route.WIRED_HEADSET);
		} else if (isVideoCall) {
			setRoute(Route.SPEAKER);
		} else {
			setRoute(Route.EARPIECE);
		}
	}

	/**
	 * Everything currently selectable — always Earpiece/Speaker, plus
	 * Wired headset / Bluetooth (with its device name) only when actually
	 * connected right now. Call this fresh each time the picker is shown
	 * rather than caching it — availability can change over the life of a
	 * call (headset plugged in/out, Bluetooth connects/disconnects).
	 */
	public synchronized List<RouteOption> getAvailableRoutes(final Context context) {
		final List<RouteOption> options = new ArrayList<>();
		options.add(new RouteOption(Route.EARPIECE, "Earpiece"));
		options.add(new RouteOption(Route.SPEAKER, "Speaker"));
		if (isWiredHeadsetConnected(context)) {
			options.add(new RouteOption(Route.WIRED_HEADSET, "Wired headset"));
		}
		final String bluetoothName = getConnectedBluetoothDeviceName(context);
		if (bluetoothName != null) {
			options.add(new RouteOption(Route.BLUETOOTH, bluetoothName));
		}
		return options;
	}

	public synchronized void setRoute(final Route route) {
		if (audioManager == null) {
			return;
		}
		if (currentRoute == Route.BLUETOOTH && route != Route.BLUETOOTH) {
			stopBluetoothSco();
		}
		currentRoute = route;
		switch (route) {
			case SPEAKER:
				audioManager.setSpeakerphoneOn(true);
				break;
			case BLUETOOTH:
				audioManager.setSpeakerphoneOn(false);
				startBluetoothSco();
				break;
			case WIRED_HEADSET:
			case EARPIECE:
			default:
				audioManager.setSpeakerphoneOn(false);
				break;
		}
	}

	public synchronized Route getCurrentRoute() {
		return currentRoute;
	}

	public synchronized void stop() {
		if (!started) {
			return;
		}
		started = false;
		if (audioManager != null) {
			if (currentRoute == Route.BLUETOOTH) {
				stopBluetoothSco();
			}
			audioManager.setSpeakerphoneOn(originalSpeakerphoneOn);
			audioManager.setMode(originalMode);
			abandonAudioFocus();
			audioManager = null;
		}
	}

	private void startBluetoothSco() {
		audioManager.startBluetoothSco();
		audioManager.setBluetoothScoOn(true);
	}

	private void stopBluetoothSco() {
		audioManager.setBluetoothScoOn(false);
		audioManager.stopBluetoothSco();
	}

	private void requestAudioFocus() {
		final AudioAttributes audioAttributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.build();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
					.setAudioAttributes(audioAttributes)
					.build();
			audioManager.requestAudioFocus(focusRequest);
		} else {
			focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
				@Override
				public void onAudioFocusChange(final int focusChange) {
				}
			};
			audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN);
		}
	}

	private void abandonAudioFocus() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
			audioManager.abandonAudioFocusRequest(focusRequest);
			focusRequest = null;
		} else if (focusChangeListener != null) {
			audioManager.abandonAudioFocus(focusChangeListener);
			focusChangeListener = null;
		}
	}

	private static boolean isWiredHeadsetConnected(final Context context) {
		final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if (am == null) {
			return false;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			for (final AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
				switch (device.getType()) {
					case AudioDeviceInfo.TYPE_WIRED_HEADSET:
					case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
					case AudioDeviceInfo.TYPE_USB_HEADSET:
						return true;
					default:
						break;
				}
			}
			return false;
		} else {
			//noinspection deprecation
			return am.isWiredHeadsetOn();
		}
	}

	/**
	 * Null if no SCO-capable (i.e. usable for a call, mic included) Bluetooth
	 * device is connected. Only available from API 23 (AudioDeviceInfo) —
	 * below that this app simply doesn't offer Bluetooth as a call route,
	 * same as the pre-existing wired-headset check already did.
	 */
	private static String getConnectedBluetoothDeviceName(final Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return null;
		}
		final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if (am == null) {
			return null;
		}
		for (final AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
			if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
				final CharSequence name = device.getProductName();
				return name != null && name.length() > 0 ? name.toString() : "Bluetooth";
			}
		}
		return null;
	}
}
