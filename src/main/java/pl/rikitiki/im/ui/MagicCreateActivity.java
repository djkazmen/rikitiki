package pl.rikitiki.im.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.security.SecureRandom;

import pl.rikitiki.im.Config;
import pl.rikitiki.im.R;
import pl.rikitiki.im.xmpp.jid.InvalidJidException;
import pl.rikitiki.im.xmpp.jid.Jid;

public class MagicCreateActivity extends XmppActivity implements TextWatcher {

	private TextView mFullJidDisplay;
	private EditText mUsername;
	private SecureRandom mRandom;

	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456780+-/#$!?";
	private static final int PW_LENGTH = 10;

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
		setContentView(R.layout.magic_create);
		mFullJidDisplay = (TextView) findViewById(R.id.full_jid);
		mUsername = (EditText) findViewById(R.id.username);
		mRandom = new SecureRandom();
		Button next = (Button) findViewById(R.id.create_account);
		next.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				String username = mUsername.getText().toString();
				if (username.contains("@") || username.length() < 3) {
					mUsername.setError(getString(R.string.invalid_username));
					mUsername.requestFocus();
				} else {
					mUsername.setError(null);
					try {
						// validate the JID shape up front; the account itself is
						// only created after phone verification succeeds server-side
						Jid.fromParts(username.toLowerCase(), Config.MAGIC_CREATE_DOMAIN, null);
						Intent intent = new Intent(MagicCreateActivity.this, PhoneVerificationActivity.class);
						intent.putExtra("username", username.toLowerCase());
						intent.putExtra("password", createPassword());
						startActivity(intent);
					} catch (InvalidJidException e) {
						mUsername.setError(getString(R.string.invalid_username));
						mUsername.requestFocus();
					}
				}
			}
		});
		mUsername.addTextChangedListener(this);
	}

	private String createPassword() {
		StringBuilder builder = new StringBuilder(PW_LENGTH);
		for(int i = 0; i < PW_LENGTH; ++i) {
			builder.append(CHARS.charAt(mRandom.nextInt(CHARS.length() - 1)));
		}
		return builder.toString();
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count, int after) {

	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before, int count) {

	}

	@Override
	public void afterTextChanged(Editable s) {
		if (s.toString().trim().length() > 0) {
			try {
				mFullJidDisplay.setVisibility(View.VISIBLE);
				Jid jid = Jid.fromParts(s.toString().toLowerCase(), Config.MAGIC_CREATE_DOMAIN, null);
				mFullJidDisplay.setText(getString(R.string.your_full_jid_will_be, jid.toString()));
			} catch (InvalidJidException e) {
				mFullJidDisplay.setVisibility(View.INVISIBLE);
			}

		} else {
			mFullJidDisplay.setVisibility(View.INVISIBLE);
		}
	}
}
