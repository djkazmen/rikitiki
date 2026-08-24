package pl.rikitiki.im.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.credentials.CreateCredentialResponse;
import androidx.credentials.CreatePasswordRequest;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.exceptions.CreateCredentialException;

import pl.rikitiki.im.Config;
import pl.rikitiki.im.R;
import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.http.RegistrationBackendConnection;
import pl.rikitiki.im.xmpp.jid.InvalidJidException;
import pl.rikitiki.im.xmpp.jid.Jid;

/**
 * Phone/OTP verification screen, launched directly from WelcomeActivity's
 * "create account" button. The phone number itself becomes the account's JID
 * localpart and the password is generated server-side — this activity never
 * invents either. The account is not created locally until the backend
 * confirms it was actually registered (or its password reset, if the phone
 * was already registered — i.e. this is also the "log in on a new device"
 * path) on msg.rikitiki.pl via ejabberd's admin API — see the php-reg/
 * project, sibling to this one under claude/.
 */
public class PhoneVerificationActivity extends XmppActivity {

	private EditText mPhoneNumber;
	private EditText mOtpCode;
	private Button mSendCode;
	private Button mResendCode;
	private Button mVerifyCode;
	private TextView mErrorMessage;
	private ProgressBar mProgress;

	private String mRequestedPhone;

	private final RegistrationBackendConnection mBackend = new RegistrationBackendConnection();

	@Override
	protected void refreshUiReal() {

	}

	@Override
	void onBackendConnected() {

	}

	@Override
	public void onStart() {
		super.onStart();
		final int theme = findTheme();
		if (this.mTheme != theme) {
			recreate();
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		if (getResources().getBoolean(R.bool.portrait_only)) {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		}
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_phone_verification);

		mPhoneNumber = findViewById(R.id.phone_number);
		mOtpCode = findViewById(R.id.otp_code);
		mSendCode = findViewById(R.id.send_code);
		mResendCode = findViewById(R.id.resend_code);
		mVerifyCode = findViewById(R.id.verify_code);
		mErrorMessage = findViewById(R.id.error_message);
		mProgress = findViewById(R.id.progress);

		mSendCode.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				requestCode();
			}
		});
		mResendCode.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				requestCode();
			}
		});
		mVerifyCode.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				verifyCode();
			}
		});
	}

	private void requestCode() {
		final String phone = mPhoneNumber.getText().toString().trim();
		if (phone.isEmpty()) {
			mPhoneNumber.setError(getString(R.string.invalid_phone_number));
			mPhoneNumber.requestFocus();
			return;
		}
		mPhoneNumber.setError(null);
		hideError();
		setLoading(true);
		mSendCode.setEnabled(false);
		mResendCode.setEnabled(false);
		mRequestedPhone = phone;
		mBackend.requestOtp(phone, new RegistrationBackendConnection.OnOtpRequested() {
			@Override
			public void onOtpRequestSuccess() {
				setLoading(false);
				mOtpCode.setEnabled(true);
				mResendCode.setEnabled(true);
				mVerifyCode.setEnabled(true);
				mOtpCode.requestFocus();
			}

			@Override
			public void onOtpRequestFailure(String error) {
				setLoading(false);
				mSendCode.setEnabled(true);
				mResendCode.setEnabled(true);
				showError(mapError(error));
			}
		});
	}

	private void verifyCode() {
		final String otp = mOtpCode.getText().toString().trim();
		if (otp.isEmpty() || mRequestedPhone == null) {
			return;
		}
		hideError();
		setLoading(true);
		mVerifyCode.setEnabled(false);
		mBackend.verifyOtp(mRequestedPhone, otp, new RegistrationBackendConnection.OnOtpVerified() {
			@Override
			public void onOtpVerifySuccess(String jid, String password) {
				setLoading(false);
				onAccountCreated(jid, password);
			}

			@Override
			public void onOtpVerifyFailure(String error) {
				setLoading(false);
				mVerifyCode.setEnabled(true);
				showError(mapError(error));
			}
		});
	}

	private void onAccountCreated(final String jidString, final String password) {
		final Jid jid;
		try {
			jid = Jid.fromString(jidString);
		} catch (InvalidJidException e) {
			showError(getString(R.string.error_unknown));
			return;
		}
		Account account = xmppConnectionService.findAccountByJid(jid);
		if (account == null) {
			account = new Account(jid, password);
			// No OPTION_REGISTER here: the backend already created this account
			// server-side via ejabberd's admin API, so in-band XMPP registration
			// is neither necessary nor wanted.
			account.setOption(Account.OPTION_DISABLED, true);
			account.setOption(Account.OPTION_MAGIC_CREATE, true);
			xmppConnectionService.createAccount(account);
		} else {
			// Phone was already registered — the backend treated this as a
			// "log in on a new device" recovery and reset the password.
			account.setPassword(password);
			account.setOption(Account.OPTION_DISABLED, false);
			xmppConnectionService.updateAccount(account);
		}
		offerToSaveCredential(account.getJid().toBareJid().toString(), password);
		final Intent intent = new Intent(PhoneVerificationActivity.this, EditAccountActivity.class);
		intent.putExtra("jid", account.getJid().toBareJid().toString());
		intent.putExtra("init", true);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		startActivity(intent);
	}

	// This is the app's only additional copy of the password beyond its own
	// local account storage — best-effort only. Failure here (no Credential
	// Manager provider configured, device without Play Services, etc.) must
	// never block account creation, since the account already exists and
	// works locally regardless of whether this succeeds.
	private void offerToSaveCredential(final String jid, final String password) {
		try {
			final CredentialManager credentialManager = CredentialManager.create(this);
			final CreatePasswordRequest request = new CreatePasswordRequest(jid, password);
			credentialManager.createCredentialAsync(this, request, new CancellationSignal(),
					ContextCompat.getMainExecutor(this),
					new CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>() {
						@Override
						public void onResult(final CreateCredentialResponse result) {
							Log.d(Config.LOGTAG, "offered to save credential for " + jid);
						}

						@Override
						public void onError(final CreateCredentialException e) {
							Log.d(Config.LOGTAG, "could not offer to save credential: " + e.getMessage());
						}
					});
		} catch (final Exception e) {
			Log.d(Config.LOGTAG, "credential manager unavailable", e);
		}
	}

	private String mapError(final String error) {
		switch (error) {
			case "rate_limited":
				return getString(R.string.error_rate_limited);
			case "invalid_otp":
				return getString(R.string.error_invalid_otp);
			case "expired":
			case "no_pending_otp":
				return getString(R.string.error_expired_otp);
			case "too_many_attempts":
				return getString(R.string.error_too_many_attempts);
			case "username_taken":
				return getString(R.string.error_username_taken);
			case "invalid_phone":
				return getString(R.string.invalid_phone_number);
			case "connection_error":
				return getString(R.string.error_connection);
			default:
				return getString(R.string.error_unknown);
		}
	}

	private void setLoading(final boolean loading) {
		mProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
	}

	private void showError(final String message) {
		mErrorMessage.setText(message);
		mErrorMessage.setVisibility(View.VISIBLE);
	}

	private void hideError() {
		mErrorMessage.setVisibility(View.GONE);
	}
}
