package pl.rikitiki.im.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.makeramen.roundedimageview.RoundedImageView;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.List;
import java.util.Locale;

import pl.rikitiki.im.R;
import pl.rikitiki.im.entities.Contact;
import pl.rikitiki.im.xmpp.jingle.CallAudioManager;
import pl.rikitiki.im.xmpp.jingle.JingleRtpConnection;
import pl.rikitiki.im.xmpp.jingle.WebRtcWrapper;

/**
 * Full-screen call UI (like a phone dialer's call screen), shown for both
 * incoming and outgoing calls instead of relying solely on the notification.
 */
public class RtpSessionActivity extends XmppActivity implements JingleRtpConnection.OnCallStateChanged {

	private RoundedImageView photoView;
	private TextView displayNameView;
	private TextView statusView;
	private ImageButton acceptButton;
	private ImageButton declineButton;
	private ImageButton hangupButton;
	private SurfaceViewRenderer remoteVideoView;
	private SurfaceViewRenderer localVideoView;
	private ImageButton switchCameraButton;
	private ImageButton speakerButton;
	private boolean videoRenderersInitialized = false;

	private JingleRtpConnection connection;
	private final Handler uiHandler = new Handler();
	private long connectedAtElapsedRealtime = 0;
	private final Runnable durationTicker = new Runnable() {
		@Override
		public void run() {
			updateDurationText();
			uiHandler.postDelayed(this, 1000);
		}
	};

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(
				android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
						| android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
						| android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
						| android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		if (getActionBar() != null) {
			getActionBar().hide();
		}
		setContentView(R.layout.activity_rtp_session);
		photoView = (RoundedImageView) findViewById(R.id.call_photo);
		displayNameView = (TextView) findViewById(R.id.call_display_name);
		statusView = (TextView) findViewById(R.id.call_status);
		acceptButton = (ImageButton) findViewById(R.id.call_accept_button);
		declineButton = (ImageButton) findViewById(R.id.call_decline_button);
		hangupButton = (ImageButton) findViewById(R.id.call_hangup_button);
		remoteVideoView = (SurfaceViewRenderer) findViewById(R.id.remote_video_view);
		localVideoView = (SurfaceViewRenderer) findViewById(R.id.local_video_view);
		switchCameraButton = (ImageButton) findViewById(R.id.call_switch_camera_button);
		speakerButton = (ImageButton) findViewById(R.id.call_speaker_button);
		final org.webrtc.EglBase.Context eglContext = WebRtcWrapper.ensureEglBase(this);
		remoteVideoView.init(eglContext, null);
		remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
		localVideoView.init(eglContext, null);
		localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
		localVideoView.setMirror(true);
		localVideoView.setZOrderMediaOverlay(true);
		videoRenderersInitialized = true;
		switchCameraButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (connection != null) {
					connection.switchCamera();
				}
			}
		});
		speakerButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				showAudioRoutePicker();
			}
		});
		acceptButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (connection != null) {
					connection.accept();
				}
			}
		});
		declineButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (connection != null) {
					connection.terminate("decline");
				}
			}
		});
		hangupButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				if (connection != null) {
					connection.terminate("success");
				}
			}
		});
	}

	@Override
	void onBackendConnected() {
		connection = xmppConnectionService.getJingleConnectionManager().getRtpConnection();
		if (connection == null) {
			finish();
			return;
		}
		connection.setOnCallStateChangedListener(this);
		final Contact contact = connection.getAccount().getRoster().getContact(connection.getCounterPart());
		displayNameView.setText(contact.getDisplayName());
		photoView.setImageBitmap(avatarService().get(contact, getPixel(140), false));
		updateUi(connection.getState());
		updateVideoUi();
		attachVideoTracksIfAvailable();
	}

	/**
	 * Shows/hides the video renderers and switches the name/status text
	 * color to stay legible over either background. Re-run on every call
	 * state change (not just once) because a call can start as video and
	 * later downgrade to audio mid-negotiation — see
	 * {@link JingleRtpConnection#receiveSessionAccept} falling back when the
	 * callee's accept doesn't include video.
	 */
	private void updateVideoUi() {
		final boolean video = connection != null && connection.isVideoCall();
		remoteVideoView.setVisibility(video ? View.VISIBLE : View.GONE);
		localVideoView.setVisibility(video ? View.VISIBLE : View.GONE);
		switchCameraButton.setVisibility(video ? View.VISIBLE : View.GONE);
		photoView.setVisibility(video ? View.GONE : View.VISIBLE);
		if (video) {
			// Name/status normally use the theme's primary text color (dark
			// in light theme, for readability on the plain background) —
			// that's invisible over the video feed, so force legible white
			// text with a shadow regardless of theme.
			displayNameView.setTextColor(android.graphics.Color.WHITE);
			statusView.setTextColor(android.graphics.Color.WHITE);
			displayNameView.setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK);
			statusView.setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK);
		} else {
			final android.util.TypedValue tv = new android.util.TypedValue();
			getTheme().resolveAttribute(R.attr.color_text_primary, tv, true);
			final int color = tv.resourceId != 0 ? androidx.core.content.ContextCompat.getColor(this, tv.resourceId) : tv.data;
			displayNameView.setTextColor(color);
			statusView.setTextColor(color);
			displayNameView.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT);
			statusView.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT);
		}
	}

	private void attachVideoTracksIfAvailable() {
		if (connection == null || !connection.isVideoCall() || !videoRenderersInitialized) {
			return;
		}
		final VideoTrack remoteTrack = connection.getRemoteVideoTrack();
		if (remoteTrack != null) {
			remoteTrack.addSink(remoteVideoView);
		}
		final VideoTrack localTrack = connection.getLocalVideoTrack();
		if (localTrack != null) {
			localTrack.addSink(localVideoView);
		}
	}

	@Override
	public void onCallStateChanged(final JingleRtpConnection.State state) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				updateUi(state);
				updateVideoUi();
				attachVideoTracksIfAvailable();
			}
		});
	}

	private void updateUi(final JingleRtpConnection.State state) {
		acceptButton.setVisibility(View.GONE);
		declineButton.setVisibility(View.GONE);
		hangupButton.setVisibility(View.GONE);
		speakerButton.setVisibility(View.GONE);
		uiHandler.removeCallbacks(durationTicker);
		switch (state) {
			case PROPOSED:
				if (connection.isInitiator()) {
					statusView.setText(R.string.call_status_calling);
					hangupButton.setVisibility(View.VISIBLE);
				} else {
					statusView.setText(R.string.incoming_call);
					acceptButton.setVisibility(View.VISIBLE);
					declineButton.setVisibility(View.VISIBLE);
				}
				break;
			case SESSION_INITIALIZED:
			case SESSION_ACCEPTED:
			case CONNECTING:
				statusView.setText(R.string.call_status_connecting);
				hangupButton.setVisibility(View.VISIBLE);
				break;
			case CONNECTED:
				connectedAtElapsedRealtime = SystemClock.elapsedRealtime();
				hangupButton.setVisibility(View.VISIBLE);
				// Only meaningful once CallAudioManager has actually picked a
				// route (it starts exactly when this state is entered — see
				// JingleRtpConnection.transition()), so this is also the
				// first point the button can show the real current state.
				speakerButton.setVisibility(View.VISIBLE);
				updateSpeakerButton();
				uiHandler.post(durationTicker);
				break;
			case TERMINATED:
			case REJECTED:
			case RETRACTED:
				statusView.setText(R.string.call_status_ended);
				uiHandler.postDelayed(new Runnable() {
					@Override
					public void run() {
						finish();
					}
				}, 1500);
				break;
		}
	}

	private static final int REQUEST_BLUETOOTH_CONNECT = 0x4201;

	private void showAudioRoutePicker() {
		if (connection == null) {
			return;
		}
		// Without this, a connected Bluetooth headset won't be offered as a
		// route at all on API 31+ (AudioDeviceInfo can't be attributed to
		// Bluetooth without it) — but Earpiece/Speaker/wired headset are
		// still fine to show regardless, so don't block the picker on it.
		hasBluetoothConnectPermission(REQUEST_BLUETOOTH_CONNECT);
		final List<CallAudioManager.RouteOption> options = connection.getAvailableAudioRoutes();
		final PopupMenu popupMenu = new PopupMenu(this, speakerButton);
		for (int i = 0; i < options.size(); i++) {
			popupMenu.getMenu().add(0, i, i, options.get(i).label);
		}
		popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(final MenuItem item) {
				if (connection != null) {
					connection.setAudioRoute(options.get(item.getItemId()).route);
					updateSpeakerButton();
				}
				return true;
			}
		});
		popupMenu.show();
	}

	private void updateSpeakerButton() {
		if (connection == null) {
			return;
		}
		final CallAudioManager.Route route = connection.getCurrentAudioRoute();
		final int icon;
		switch (route) {
			case SPEAKER:
				icon = R.drawable.ic_action_speaker;
				break;
			case BLUETOOTH:
				icon = R.drawable.ic_action_bluetooth;
				break;
			case WIRED_HEADSET:
			case EARPIECE:
			default:
				icon = R.drawable.ic_action_call;
				break;
		}
		speakerButton.setImageResource(icon);
		speakerButton.setBackgroundResource(route == CallAudioManager.Route.EARPIECE
				? R.drawable.call_overlay_button_background
				: R.drawable.call_overlay_button_background_active);
	}

	private void updateDurationText() {
		final long seconds = (SystemClock.elapsedRealtime() - connectedAtElapsedRealtime) / 1000;
		statusView.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
	}

	@Override
	protected void onDestroy() {
		uiHandler.removeCallbacks(durationTicker);
		if (connection != null) {
			connection.setOnCallStateChangedListener(null);
		}
		if (videoRenderersInitialized) {
			remoteVideoView.release();
			localVideoView.release();
		}
		super.onDestroy();
	}

	@Override
	protected void refreshUiReal() {
	}

	@Override
	public void onBackPressed() {
		// Swallow back-press while a call is active instead of leaving the
		// call screen behind it (matches a normal phone call screen); once
		// the call ends the activity finishes itself.
	}
}
