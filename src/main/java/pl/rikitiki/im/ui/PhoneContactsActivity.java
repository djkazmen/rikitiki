package pl.rikitiki.im.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import pl.rikitiki.im.Config;
import pl.rikitiki.im.R;
import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.entities.Contact;
import pl.rikitiki.im.entities.Conversation;
import pl.rikitiki.im.services.XmppConnectionService;
import pl.rikitiki.im.utils.OnPhoneContactsLoadedListener;
import pl.rikitiki.im.utils.PhoneHelper;
import pl.rikitiki.im.xmpp.jid.InvalidJidException;
import pl.rikitiki.im.xmpp.jid.Jid;

/**
 * Matches the phone's contacts against Rikitiki accounts by phone number
 * (accounts are registered with the phone number as their JID localpart —
 * see php-reg/). A contact whose number matches an existing roster contact
 * can be opened as a conversation directly; everyone else gets an SMS
 * invite instead.
 *
 * This only trusts the roster (contacts already added on this account) —
 * there is no reliable way to ask the server "does this JID have an
 * account" for a number that isn't already a roster contact. Both stanzas
 * that look plausible for that were tried and ruled out against this
 * server: vcard-temp returns a valid empty-vCard RESULT for any JID
 * (registered or not, since mod_vcard just answers from its own storage
 * table without checking the user table), and disco#info returns an
 * identical subscription-required error for any JID not already in the
 * roster (registered or not, since the privacy policy blocks it before
 * existence is ever checked). Neither can distinguish "exists" from
 * "doesn't exist" for a number you haven't already added.
 */
public class PhoneContactsActivity extends XmppActivity implements XmppConnectionService.OnRosterUpdate {

	private static final int REQUEST_READ_CONTACTS = 0x0301;

	private ListView mListView;
	private ProgressBar mProgress;
	// The full, unfiltered set loaded from the phone's contacts. mEntries
	// (below) is the filtered subset actually shown in the list — with a
	// large enough contact list, scrolling to find someone isn't realistic,
	// so a search box is the primary way to find them.
	private final List<Entry> mAllEntries = new ArrayList<>();
	private final List<Entry> mEntries = new ArrayList<>();
	private EntryAdapter mAdapter;
	private boolean mLoaded = false;
	private MenuItem mMenuSearchView;
	private EditText mSearchEditText;
	private String mCurrentQuery = null;

	private static class Entry {
		String displayName;
		String rawNumber;
		Jid jid; // null if the number couldn't be normalized into a JID at all
		Account account;
		boolean existsOnServer;
	}

	@Override
	protected void refreshUiReal() {
		// Runs on the UI thread via XmppActivity.refreshUi() — onRosterUpdate()
		// can otherwise fire from the XMPP connection's background thread.
		refreshExistsFlags();
	}

	@Override
	void onBackendConnected() {
		final boolean init = getIntent() != null && getIntent().getBooleanExtra("init", false);
		final boolean noConversations = xmppConnectionService.getConversations().isEmpty();
		if ((init || noConversations) && getActionBar() != null) {
			getActionBar().setDisplayHomeAsUpEnabled(false);
			getActionBar().setHomeButtonEnabled(false);
		}
		if (!mLoaded && hasReadContactsPermission(REQUEST_READ_CONTACTS)) {
			loadContacts();
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_phone_contacts);
		if (getActionBar() != null) {
			getActionBar().setDisplayHomeAsUpEnabled(true);
			getActionBar().setTitle(R.string.phone_contacts_title);
		}
		mListView = findViewById(R.id.phone_contacts_list);
		mProgress = findViewById(R.id.phone_contacts_progress);
		mAdapter = new EntryAdapter();
		mListView.setAdapter(mAdapter);
		mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(final AdapterView<?> parent, final View view, final int position, final long id) {
				onEntryClicked(mEntries.get(position));
			}
		});
		mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(final AdapterView<?> parent, final View view, final int position, final long id) {
				onEntryLongClicked(mEntries.get(position));
				return true;
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.phone_contacts, menu);
		mMenuSearchView = menu.findItem(R.id.action_search);
		mMenuSearchView.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
			@Override
			public boolean onMenuItemActionExpand(final MenuItem item) {
				mSearchEditText.post(new Runnable() {
					@Override
					public void run() {
						mSearchEditText.requestFocus();
						final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
						imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
					}
				});
				return true;
			}

			@Override
			public boolean onMenuItemActionCollapse(final MenuItem item) {
				hideKeyboard();
				mSearchEditText.setText("");
				filter(null);
				return true;
			}
		});
		final View searchView = mMenuSearchView.getActionView();
		mSearchEditText = (EditText) searchView.findViewById(R.id.search_field);
		mSearchEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
			}

			@Override
			public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
			}

			@Override
			public void afterTextChanged(final Editable editable) {
				filter(editable.toString());
			}
		});
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == R.id.action_advanced_contacts) {
			startActivity(new Intent(this, StartConversationActivity.class));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onRequestPermissionsResult(final int requestCode, final String[] permissions, final int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_READ_CONTACTS
				&& grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED
				&& xmppConnectionService != null) {
			loadContacts();
		}
	}

	private void loadContacts() {
		mLoaded = true;
		mProgress.setVisibility(View.VISIBLE);
		PhoneHelper.loadPhoneContactsWithNumbers(this, new OnPhoneContactsLoadedListener() {
			@Override
			public void onPhoneContactsLoaded(final List<Bundle> phoneContacts) {
				onContactsLoaded(phoneContacts);
			}
		});
	}

	private void onContactsLoaded(final List<Bundle> phoneContacts) {
		mAllEntries.clear();
		final List<Account> accounts = xmppConnectionService.getAccounts();
		for (final Bundle phoneContact : phoneContacts) {
			final String rawNumber = phoneContact.getString("phonenumber");
			final String displayName = phoneContact.getString("displayname");
			if (rawNumber == null || displayName == null || accounts.isEmpty()) {
				continue;
			}
			final Entry entry = new Entry();
			entry.displayName = displayName;
			entry.rawNumber = rawNumber;
			entry.account = accounts.get(0);
			final String localpart = PhoneHelper.normalizeToJidLocalpart(this, rawNumber);
			if (localpart != null) {
				try {
					entry.jid = Jid.fromParts(localpart, Config.MAGIC_CREATE_DOMAIN, null);
				} catch (final InvalidJidException e) {
					entry.jid = null;
				}
			}
			mAllEntries.add(entry);
		}
		mProgress.setVisibility(View.GONE);
		refreshExistsFlags();
		filter(mCurrentQuery);
	}

	// Case-insensitive match against the contact's display name or raw phone
	// number — a null/empty query shows everything.
	private void filter(final String query) {
		mCurrentQuery = query;
		mEntries.clear();
		if (query == null || query.trim().isEmpty()) {
			mEntries.addAll(mAllEntries);
		} else {
			final String needle = query.trim().toLowerCase();
			for (final Entry entry : mAllEntries) {
				if (entry.displayName.toLowerCase().contains(needle)
						|| entry.rawNumber.toLowerCase().contains(needle)) {
					mEntries.add(entry);
				}
			}
		}
		if (mAdapter != null) {
			mAdapter.notifyDataSetChanged();
		}
	}

	// The roster (the only source of truth now, see class javadoc) can still
	// be loading — from the local DB or from the server — at the moment the
	// phone contacts finish querying, especially right after a fresh app
	// launch. Re-deriving existsOnServer from the roster on every roster
	// update (not just once at load time) means a real contact that briefly
	// raced the roster load self-corrects instead of staying wrong for the
	// rest of this screen's lifetime. Goes through refreshUi() (which
	// dispatches to the UI thread) rather than recomputing directly, since
	// this can fire from the XMPP connection's background thread.
	@Override
	public void onRosterUpdate() {
		refreshUi();
	}

	private void refreshExistsFlags() {
		for (final Entry entry : mAllEntries) {
			final Contact existing = entry.jid == null ? null : entry.account.getRoster().getContactFromRoster(entry.jid);
			entry.existsOnServer = existing != null && existing.showInRoster();
		}
		if (mAdapter != null) {
			mAdapter.notifyDataSetChanged();
		}
	}

	private void onEntryClicked(final Entry entry) {
		if (entry.jid != null && entry.existsOnServer) {
			final Contact contact = entry.account.getRoster().getContact(entry.jid);
			if (!contact.showInRoster()) {
				xmppConnectionService.createContact(contact);
			}
			final Conversation conversation = xmppConnectionService.findOrCreateConversation(entry.account, entry.jid, false, true);
			switchToConversation(conversation);
		} else {
			inviteViaSms(entry);
		}
	}

	// Since the roster is the only source of truth for "on Rikitiki" (see
	// class javadoc), a phone contact who genuinely has an account but
	// isn't a roster contact yet has no other way to surface here — this
	// lets the user add them manually instead of relying on auto-discovery.
	private void onEntryLongClicked(final Entry entry) {
		if (entry.jid == null) {
			Toast.makeText(this, R.string.phone_contact_number_not_valid, Toast.LENGTH_SHORT).show();
			return;
		}
		if (entry.existsOnServer) {
			onEntryClicked(entry);
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.add_phone_contact_title)
				.setMessage(getString(R.string.add_phone_contact_message, entry.displayName, entry.displayName))
				.setPositiveButton(R.string.add_contact, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(final DialogInterface dialog, final int which) {
						final Contact contact = entry.account.getRoster().getContact(entry.jid);
						xmppConnectionService.createContact(contact);
						entry.existsOnServer = true;
						mAdapter.notifyDataSetChanged();
						final Conversation conversation = xmppConnectionService.findOrCreateConversation(entry.account, entry.jid, false, true);
						switchToConversation(conversation);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void inviteViaSms(final Entry entry) {
		final Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + entry.rawNumber));
		intent.putExtra("sms_body", getString(R.string.invite_sms_body));
		try {
			startActivity(intent);
		} catch (final ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show();
		}
	}

	private class EntryAdapter extends ArrayAdapter<Entry> {
		EntryAdapter() {
			super(PhoneContactsActivity.this, 0, mEntries);
		}

		@Override
		public View getView(final int position, View view, final ViewGroup parent) {
			if (view == null) {
				view = getLayoutInflater().inflate(R.layout.phone_contact_row, parent, false);
			}
			final Entry entry = mEntries.get(position);
			((TextView) view.findViewById(R.id.phone_contact_name)).setText(entry.displayName);
			final TextView status = (TextView) view.findViewById(R.id.phone_contact_status);
			if (entry.jid != null && entry.existsOnServer) {
				status.setText(R.string.phone_contact_on_rikitiki);
			} else {
				status.setText(R.string.phone_contact_invite_sms);
			}
			return view;
		}
	}
}
