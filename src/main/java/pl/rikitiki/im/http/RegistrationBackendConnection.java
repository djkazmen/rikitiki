package pl.rikitiki.im.http;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import pl.rikitiki.im.Config;

/**
 * Talks to the Rikitiki registration backend (phone/OTP verification in front
 * of ejabberd's admin API on msg.rikitiki.pl). The backend holds the SMSAPI.pl
 * and ejabberd admin credentials; this class never sees either.
 */
public class RegistrationBackendConnection {

	private static final int TIMEOUT_MS = 15000;

	private final Handler mainThread = new Handler(Looper.getMainLooper());

	public interface OnOtpRequested {
		void onOtpRequestSuccess();
		void onOtpRequestFailure(String error);
	}

	public interface OnOtpVerified {
		void onOtpVerifySuccess(String jid);
		void onOtpVerifyFailure(String error);
	}

	public void requestOtp(final String phone, final OnOtpRequested callback) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					final JSONObject body = new JSONObject();
					body.put("phone", phone);
					final JSONObject response = post(Config.REGISTRATION_BACKEND_URL + "/otp_request.php", body);
					if (response.optBoolean("success", false)) {
						mainThread.post(new Runnable() {
							@Override
							public void run() {
								callback.onOtpRequestSuccess();
							}
						});
					} else {
						postRequestFailure(callback, response.optString("error", "unknown_error"));
					}
				} catch (final Exception e) {
					postRequestFailure(callback, "connection_error");
				}
			}
		}).start();
	}

	public void verifyOtp(final String phone, final String otp, final String username, final String password, final OnOtpVerified callback) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					final JSONObject body = new JSONObject();
					body.put("phone", phone);
					body.put("otp", otp);
					body.put("username", username);
					body.put("password", password);
					final JSONObject response = post(Config.REGISTRATION_BACKEND_URL + "/otp_verify.php", body);
					if (response.optBoolean("success", false)) {
						final String jid = response.optString("jid");
						mainThread.post(new Runnable() {
							@Override
							public void run() {
								callback.onOtpVerifySuccess(jid);
							}
						});
					} else {
						postVerifyFailure(callback, response.optString("error", "unknown_error"));
					}
				} catch (final Exception e) {
					postVerifyFailure(callback, "connection_error");
				}
			}
		}).start();
	}

	private void postRequestFailure(final OnOtpRequested callback, final String error) {
		mainThread.post(new Runnable() {
			@Override
			public void run() {
				callback.onOtpRequestFailure(error);
			}
		});
	}

	private void postVerifyFailure(final OnOtpVerified callback, final String error) {
		mainThread.post(new Runnable() {
			@Override
			public void run() {
				callback.onOtpVerifyFailure(error);
			}
		});
	}

	private JSONObject post(final String urlString, final JSONObject body) throws IOException, org.json.JSONException {
		final HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
		try {
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			final OutputStream os = connection.getOutputStream();
			try {
				os.write(body.toString().getBytes(StandardCharsets.UTF_8));
			} finally {
				os.close();
			}
			final int code = connection.getResponseCode();
			final InputStream is = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
			final String responseBody = readStream(is);
			return new JSONObject(responseBody);
		} finally {
			connection.disconnect();
		}
	}

	private static String readStream(final InputStream is) throws IOException {
		final ByteArrayOutputStream result = new ByteArrayOutputStream();
		final byte[] buffer = new byte[1024];
		int length;
		while ((length = is.read(buffer)) != -1) {
			result.write(buffer, 0, length);
		}
		return result.toString("UTF-8");
	}
}
