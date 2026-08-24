package pl.rikitiki.im.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import pl.rikitiki.im.Config;

/**
 * Loops an incoming-call ringtone and/or vibrates until stop() is called.
 * Mirrors VoiceRecorder's start()/stop() shape: fire-and-forget, no
 * completion callback needed since nothing waits on it finishing.
 */
public class CallRingtonePlayer {

	private static final long[] VIBRATE_PATTERN = {0, 1000, 1000};

	private MediaPlayer mediaPlayer;
	private Vibrator vibrator;
	private AudioManager audioManager;
	private AudioManager.OnAudioFocusChangeListener focusChangeListener;
	private AudioFocusRequest focusRequest;
	private volatile boolean stopped = true;

	public synchronized void start(final Context context, final boolean vibrate, final Uri ringtoneUri) {
		stop();
		stopped = false;
		final AudioAttributes audioAttributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build();
		requestAudioFocus(context, audioAttributes);
		if (ringtoneUri != null) {
			// prepare() is synchronous; run it off-thread so a slow/missing
			// ringtone file can't stall whichever thread calls start() here.
			new Thread(new Runnable() {
				@Override
				public void run() {
					startRingtone(context, ringtoneUri, audioAttributes);
				}
			}, "call-ringtone").start();
		}
		if (vibrate) {
			startVibration(context, audioAttributes);
		}
	}

	private void startRingtone(final Context context, final Uri ringtoneUri, final AudioAttributes audioAttributes) {
		try {
			final MediaPlayer player = new MediaPlayer();
			player.setAudioAttributes(audioAttributes);
			player.setDataSource(context, ringtoneUri);
			player.setLooping(true);
			player.prepare();
			synchronized (this) {
				if (stopped) {
					player.release();
					return;
				}
				mediaPlayer = player;
				player.start();
			}
		} catch (final Exception e) {
			Log.d(Config.LOGTAG, "call ringtone playback failed", e);
		}
	}

	private void startVibration(final Context context, final AudioAttributes audioAttributes) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			final VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
			vibrator = manager == null ? null : manager.getDefaultVibrator();
		} else {
			vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
		}
		if (vibrator == null || !vibrator.hasVibrator()) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			vibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0), audioAttributes);
		} else {
			vibrator.vibrate(VIBRATE_PATTERN, 0);
		}
	}

	private void requestAudioFocus(final Context context, final AudioAttributes audioAttributes) {
		audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if (audioManager == null) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
					.setAudioAttributes(audioAttributes)
					.build();
			audioManager.requestAudioFocus(focusRequest);
		} else {
			focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
				@Override
				public void onAudioFocusChange(final int focusChange) {
				}
			};
			audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
		}
	}

	public synchronized void stop() {
		stopped = true;
		if (mediaPlayer != null) {
			try {
				mediaPlayer.stop();
			} catch (final Exception ignored) {
			}
			mediaPlayer.release();
			mediaPlayer = null;
		}
		if (vibrator != null) {
			vibrator.cancel();
			vibrator = null;
		}
		if (audioManager != null) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
				audioManager.abandonAudioFocusRequest(focusRequest);
				focusRequest = null;
			} else if (focusChangeListener != null) {
				audioManager.abandonAudioFocus(focusChangeListener);
				focusChangeListener = null;
			}
			audioManager = null;
		}
	}
}
