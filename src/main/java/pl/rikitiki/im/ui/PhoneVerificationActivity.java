package pl.rikitiki.im.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import pl.rikitiki.im.Config;
import pl.rikitiki.im.R;
import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.http.RegistrationBackendConnection;
import pl.rikitiki.im.xmpp.jid.InvalidJidException;
import pl.rikitiki.im.xmpp.jid.Jid;

/**
 * Phone/OTP verification step inserted between MagicCreateActivity (username
 * picking) and EditAccountActivity. The account is not created locally until
 * the backend confirms it was actually registered on msg.rikitiki.pl via
 * ejabberd's admin API — see the php-reg/ project, sibling to this one under claude/.
 */
public class PhoneVerificationActivity extends XmppActivity {

	private EditText mPhoneNumber;
	private EditText mOtpCode;
	private Button mSendCode;
	private Button mResendCode;
	private Button mVerifyCode;
	private TextView mErrorMessage;
	private ProgressBar mProgress;

	private String mUsername;
	private String mPassword;
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

		mUsername = getIntent().getStringExtra("username");
		mPassword = getIntent().getStringExtra("password");

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
		mBackend.verifyOtp(mRequestedPhone, otp, mUsername, mPassword, new RegistrationBackendConnection.OnOtpVerified() {
			@Override
			public void onOtpVerifySuccess(String jid) {
				setLoading(false);
				onAccountCreated(jid);
			}

			@Override
			public void onOtpVerifyFailure(String error) {
				setLoading(false);
				mVerifyCode.setEnabled(true);
				showError(mapError(error));
			}
		});
	}

	private void onAccountCreated(final String jidString) {
		final Jid jid;
		try {
			jid = Jid.fromString(jidString);
		} catch (InvalidJidException e) {
			showError(getString(R.string.error_unknown));
			return;
		}
		Account account = xmppConnectionService.findAccountByJid(jid);
		if (account == null) {
			account = new Account(jid, mPassword);
			// No OPTION_REGISTER here: the backend already created this account
			// server-side via ejabberd's admin API, so in-band XMPP registration
			// is neither necessary nor wanted.
			account.setOption(Account.OPTION_DISABLED, true);
			account.setOption(Account.OPTION_MAGIC_CREATE, true);
			xmppConnectionService.createAccount(account);
		}
		final Intent intent = new Intent(PhoneVerificationActivity.this, EditAccountActivity.class);
		intent.putExtra("jid", account.getJid().toBareJid().toString());
		intent.putExtra("init", true);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		startActivity(intent);
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
