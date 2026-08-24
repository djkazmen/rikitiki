package pl.rikitiki.im.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import androidx.slidingpanelayout.widget.SlidingPaneLayout;
import androidx.slidingpanelayout.widget.SlidingPaneLayout.PanelSlideListener;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import net.java.otr4j.session.SessionStatus;

import org.openintents.openpgp.util.OpenPgpApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.timroes.android.listview.EnhancedListView;
import pl.rikitiki.im.Config;
import pl.rikitiki.im.R;
import pl.rikitiki.im.crypto.axolotl.AxolotlService;
import pl.rikitiki.im.crypto.axolotl.FingerprintStatus;
import pl.rikitiki.im.entities.Account;
import pl.rikitiki.im.entities.Blockable;
import pl.rikitiki.im.entities.Contact;
import pl.rikitiki.im.entities.Conversation;
import pl.rikitiki.im.entities.Message;
import pl.rikitiki.im.entities.Transferable;
import pl.rikitiki.im.persistance.FileBackend;
import pl.rikitiki.im.services.XmppConnectionService;
import pl.rikitiki.im.services.XmppConnectionService.OnAccountUpdate;
import pl.rikitiki.im.services.XmppConnectionService.OnConversationUpdate;
import pl.rikitiki.im.services.XmppConnectionService.OnRosterUpdate;
import pl.rikitiki.im.ui.adapter.ConversationAdapter;
import pl.rikitiki.im.utils.ExceptionHelper;
import pl.rikitiki.im.utils.UIHelper;
import pl.rikitiki.im.xmpp.OnUpdateBlocklist;
import pl.rikitiki.im.xmpp.XmppConnection;
import pl.rikitiki.im.xmpp.jid.InvalidJidException;
import pl.rikitiki.im.xmpp.jid.Jid;

public class ConversationActivity extends XmppActivity
	implements OnAccountUpdate, OnConversationUpdate, OnRosterUpdate, OnUpdateBlocklist, XmppConnectionService.OnShowErrorToast {

	public static final String ACTION_VIEW_CONVERSATION = "pl.rikitiki.im.action.VIEW";
	public static final String CONVERSATION = "conversationUuid";
	public static final String EXTRA_DOWNLOAD_UUID = "pl.rikitiki.im.download_uuid";
	public static final String TEXT = "text";
	public static final String NICK = "nick";
	public static final String PRIVATE_MESSAGE = "pm";

	public static final int REQUEST_SEND_MESSAGE = 0x0201;
	public static final int REQUEST_DECRYPT_PGP = 0x0202;
	public static final int REQUEST_ENCRYPT_MESSAGE = 0x0207;
	public static final int REQUEST_TRUST_KEYS_TEXT = 0x0208;
	public static final int REQUEST_TRUST_KEYS_MENU = 0x0209;
	public static final int REQUEST_START_DOWNLOAD = 0x0210;
	public static final int REQUEST_CALL = 0x0211;
	public static final int REQUEST_NOTIFICATION_PERMISSION = 0x0212;
	public static final int REQUEST_RECORD_VOICE_MESSAGE = 0x0213;
	public static final int REQUEST_CALL_VIDEO = 0x0214;
	public static final int REQUEST_CREATE_CONFERENCE = 0x0215;
	public static final int ATTACHMENT_CHOICE_CHOOSE_IMAGE = 0x0301;
	public static final int ATTACHMENT_CHOICE_TAKE_PHOTO = 0x0302;
	public static final int ATTACHMENT_CHOICE_CHOOSE_FILE = 0x0303;
	public static final int ATTACHMENT_CHOICE_RECORD_VOICE = 0x0304;
	public static final int ATTACHMENT_CHOICE_LOCATION = 0x0305;
	public static final int ATTACHMENT_CHOICE_INVALID = 0x0306;
	private static final String STATE_OPEN_CONVERSATION = "state_open_conversation";
	private static final String STATE_PANEL_OPEN = "state_panel_open";
	private static final String STATE_PENDING_URI = "state_pending_uri";
	private static final String STATE_FIRST_VISIBLE = "first_visible";
	private static final String STATE_OFFSET_FROM_TOP = "offset_from_top";

	private String mOpenConversation = null;
	private boolean mPanelOpen = true;
	private AtomicBoolean mShouldPanelBeOpen = new AtomicBoolean(false);
	private Pair<Integer,Integer> mScrollPosition = null;
	final private List<Uri> mPendingImageUris = new ArrayList<>();
	final private List<Uri> mPendingFileUris = new ArrayList<>();
	private Uri mPendingGeoUri = null;
	private pl.rikitiki.im.utils.VoiceRecorder mVoiceRecorder = null;
	private java.io.File mVoiceRecordingFile = null;
	private android.app.AlertDialog mVoiceRecordingDialog = null;
	private final Handler mVoiceRecordingHandler = new Handler();
	private long mVoiceRecordingStartedAt = 0;
	private boolean mVoiceRecordingCancelled = false;
	private boolean forbidProcessingPendings = false;
	private Message mPendingDownloadableMessage = null;
	private boolean mPendingCallIsVideo = false;

	private boolean conversationWasSelectedByKeyboard = false;

	private View mContentView;

	private List<Conversation> conversationList = new ArrayList<>();
	private final List<Contact> contactsWithoutConversation = new ArrayList<>();
	private LinearLayout contactsWithoutConversationContainer;
	private Conversation swipedConversation = null;
	private Conversation mSelectedConversation = null;
	private EnhancedListView listView;
	private ConversationFragment mConversationFragment;

	// Which of the "Chats"/"Rooms" tabs is active — conversationList is
	// filtered down to this mode every time it's rebuilt
	// (updateConversationList()), so everything already indexing into
	// conversationList (click handling, swipe-to-dismiss, keyboard
	// navigation) works unchanged, just against a narrower list.
	//
	// Deliberately a plain custom view row (two TextViews, see
	// fragment_conversations_overview.xml's "conversation_tabs" row), not
	// android.app.ActionBar.NAVIGATION_MODE_TABS. That was tried first (it's
	// already used elsewhere in this codebase, in StartConversationActivity)
	// but its content-frame height accounting for the extra tab row turned
	// out to be inconsistent across devices: it required a manually-measured
	// compensating padding fix to avoid the tab row overlapping the list on
	// the emulator, but that same padding then double-compensated on a real
	// Samsung phone, which apparently already reserves the right amount of
	// space on its own. A plain view in our own layout has no such
	// OEM-dependent ActionBar sizing behavior to fight — it just occupies
	// normal, predictable space above the list, on every device.
	private int mConversationsTabMode = Conversation.MODE_SINGLE;
	private TextView mChatsTab;
	private TextView mRoomsTab;

	private void selectConversationsTab(final int mode) {
		if (mode == mConversationsTabMode) {
			return;
		}
		mConversationsTabMode = mode;
		if (mChatsTab != null && mRoomsTab != null) {
			mChatsTab.setSelected(mode == Conversation.MODE_SINGLE);
			mRoomsTab.setSelected(mode == Conversation.MODE_MULTI);
		}
		if (xmppConnectionService != null) {
			updateConversationList();
			showConversationsOverview();
		}
	}

	private Toast mCreateConferenceToast;
	private final UiCallback<Conversation> mAdhocConferenceCallback = new UiCallback<Conversation>() {
		@Override
		public void success(final Conversation conversation) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (mCreateConferenceToast != null) {
						mCreateConferenceToast.cancel();
					}
					setSelectedConversation(conversation);
					mConversationFragment.reInit(conversation);
					hideConversationsOverview();
					openConversation();
				}
			});
		}

		@Override
		public void error(final int errorCode, Conversation object) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (mCreateConferenceToast != null) {
						mCreateConferenceToast.cancel();
					}
					mCreateConferenceToast = Toast.makeText(ConversationActivity.this, errorCode, Toast.LENGTH_LONG);
					mCreateConferenceToast.show();
				}
			});
		}

		@Override
		public void userInputRequried(PendingIntent pi, Conversation object) {
		}
	};

	// Reuses the same "pick an account, name it, choose participants" flow
	// already built and working in StartConversationActivity — invoked from
	// the "+" button here when the Rooms tab is active (see
	// onOptionsItemSelected()'s action_add handling), so creating a room and
	// inviting people works from the tab it's actually about, instead of
	// only being reachable via the "Jabber ID & conferences" escape hatch.
	private void showCreateConferenceDialog() {
		final List<String> activatedAccounts = new ArrayList<>();
		for (final Account account : xmppConnectionService.getAccounts()) {
			if (account.getStatus() != Account.State.DISABLED) {
				if (Config.DOMAIN_LOCK != null) {
					activatedAccounts.add(account.getJid().getLocalpart());
				} else {
					activatedAccounts.add(account.getJid().toBareJid().toString());
				}
			}
		}
		final AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.create_conference);
		final View dialogView = getLayoutInflater().inflate(R.layout.create_conference_dialog, null);
		final Spinner spinner = (Spinner) dialogView.findViewById(R.id.account);
		final EditText subject = (EditText) dialogView.findViewById(R.id.subject);
		StartConversationActivity.populateAccountSpinner(this, activatedAccounts, spinner);
		builder.setView(dialogView);
		builder.setPositiveButton(R.string.choose_participants, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (!xmppConnectionServiceBound || !spinner.isEnabled()) {
					return;
				}
				final Jid accountJid;
				try {
					if (Config.DOMAIN_LOCK != null) {
						accountJid = Jid.fromParts((String) spinner.getSelectedItem(), Config.DOMAIN_LOCK, null);
					} else {
						accountJid = Jid.fromString((String) spinner.getSelectedItem());
					}
				} catch (final InvalidJidException e) {
					return;
				}
				final Account account = xmppConnectionService.findAccountByJid(accountJid);
				if (account == null) {
					return;
				}
				final Intent intent = new Intent(getApplicationContext(), ChooseContactActivity.class);
				intent.putExtra("multiple", true);
				intent.putExtra("show_enter_jid", true);
				intent.putExtra("subject", subject.getText().toString());
				intent.putExtra(EXTRA_ACCOUNT, account.getJid().toBareJid().toString());
				intent.putExtra(ChooseContactActivity.EXTRA_TITLE_RES_ID, R.string.choose_participants);
				startActivityForResult(intent, REQUEST_CREATE_CONFERENCE);
			}
		});
		builder.setNegativeButton(R.string.cancel, null);
		builder.create().show();
	}

	private ArrayAdapter<Conversation> listAdapter;

	private boolean mActivityPaused = false;
	private AtomicBoolean mRedirected = new AtomicBoolean(false);
	private Pair<Integer, Intent> mPostponedActivityResult;
	private boolean mUnprocessedNewIntent = false;

	public Conversation getSelectedConversation() {
		return this.mSelectedConversation;
	}

	public void setSelectedConversation(Conversation conversation) {
		this.mSelectedConversation = conversation;
	}

	public void showConversationsOverview() {
		if (mContentView instanceof SlidingPaneLayout) {
			SlidingPaneLayout mSlidingPaneLayout = (SlidingPaneLayout) mContentView;
			mShouldPanelBeOpen.set(true);
			// AndroidX SlidingPaneLayout semantics (unlike the old support-v4 version):
			// closePane() closes the DETAIL view, revealing the list/overview pane.
			mSlidingPaneLayout.closePane();
		}
	}

	@Override
	protected String getShareableUri() {
		Conversation conversation = getSelectedConversation();
		if (conversation != null) {
			return conversation.getAccount().getShareableUri();
		} else {
			return "";
		}
	}

	public void hideConversationsOverview() {
		if (mContentView instanceof SlidingPaneLayout) {
			SlidingPaneLayout mSlidingPaneLayout = (SlidingPaneLayout) mContentView;
			mShouldPanelBeOpen.set(false);
			// openPane() opens the DETAIL view (the selected conversation), hiding the overview.
			mSlidingPaneLayout.openPane();
		}
	}

	public boolean isConversationsOverviewHideable() {
		return mContentView instanceof SlidingPaneLayout;
	}

	public boolean isConversationsOverviewVisable() {
		if (mContentView instanceof SlidingPaneLayout) {
			return mShouldPanelBeOpen.get();
		} else {
			return true;
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestNotificationPermissionIfNeeded(REQUEST_NOTIFICATION_PERMISSION);
		if (savedInstanceState != null) {
			mOpenConversation = savedInstanceState.getString(STATE_OPEN_CONVERSATION, null);
			mPanelOpen = savedInstanceState.getBoolean(STATE_PANEL_OPEN, true);
			int pos = savedInstanceState.getInt(STATE_FIRST_VISIBLE, -1);
			int offset = savedInstanceState.getInt(STATE_OFFSET_FROM_TOP, 1);
			if (pos >= 0 && offset <= 0) {
				Log.d(Config.LOGTAG,"retrieved scroll position from instanceState "+pos+":"+offset);
				mScrollPosition = new Pair<>(pos,offset);
			} else {
				mScrollPosition = null;
			}
			String pending = savedInstanceState.getString(STATE_PENDING_URI, null);
			if (pending != null) {
				Log.d(Config.LOGTAG,"ConversationsActivity.onCreate() - restoring pending image uri");
				mPendingImageUris.clear();
				mPendingImageUris.add(Uri.parse(pending));
			}
		}

		setContentView(R.layout.fragment_conversations_overview);

		mChatsTab = (TextView) findViewById(R.id.tab_chats);
		mRoomsTab = (TextView) findViewById(R.id.tab_rooms);
		if (mChatsTab != null && mRoomsTab != null) {
			mChatsTab.setSelected(mConversationsTabMode == Conversation.MODE_SINGLE);
			mRoomsTab.setSelected(mConversationsTabMode == Conversation.MODE_MULTI);
			mChatsTab.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(final View v) {
					selectConversationsTab(Conversation.MODE_SINGLE);
				}
			});
			mRoomsTab.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(final View v) {
					selectConversationsTab(Conversation.MODE_MULTI);
				}
			});
		}

		this.mConversationFragment = new ConversationFragment();
		FragmentTransaction transaction = getFragmentManager().beginTransaction();
		transaction.replace(R.id.selected_conversation, this.mConversationFragment, "conversation");
		transaction.commit();

		listView = (EnhancedListView) findViewById(R.id.list);
		contactsWithoutConversationContainer = new LinearLayout(this);
		contactsWithoutConversationContainer.setOrientation(LinearLayout.VERTICAL);
		// isSelectable=false so taps land on each row's own OnClickListener instead of
		// listView's OnItemClickListener/OnDismissCallback, which assume every position
		// indexes into conversationList/listAdapter.
		listView.addFooterView(contactsWithoutConversationContainer, null, false);
		this.listAdapter = new ConversationAdapter(this, conversationList);
		listView.setAdapter(this.listAdapter);

		listView.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> arg0, View clickedView,
									int position, long arg3) {
				if (getSelectedConversation() != conversationList.get(position)) {
					setSelectedConversation(conversationList.get(position));
					ConversationActivity.this.mConversationFragment.reInit(getSelectedConversation());
					conversationWasSelectedByKeyboard = false;
				}
				hideConversationsOverview();
				openConversation();
			}
		});

		listView.setDismissCallback(new EnhancedListView.OnDismissCallback() {

			@Override
			public EnhancedListView.Undoable onDismiss(final EnhancedListView enhancedListView, final int position) {

				final int index = listView.getFirstVisiblePosition();
				View v = listView.getChildAt(0);
				final int top = (v == null) ? 0 : (v.getTop() - listView.getPaddingTop());

				try {
					swipedConversation = listAdapter.getItem(position);
				} catch (IndexOutOfBoundsException e) {
					return null;
				}
				listAdapter.remove(swipedConversation);
				xmppConnectionService.markRead(swipedConversation);

				final boolean formerlySelected = (getSelectedConversation() == swipedConversation);
				if (position == 0 && listAdapter.getCount() == 0) {
					endConversation(swipedConversation, false, true);
					return null;
				} else if (formerlySelected) {
					setSelectedConversation(listAdapter.getItem(0));
					ConversationActivity.this.mConversationFragment
							.reInit(getSelectedConversation());
				}

				return new EnhancedListView.Undoable() {

					@Override
					public void undo() {
						listAdapter.insert(swipedConversation, position);
						if (formerlySelected) {
							setSelectedConversation(swipedConversation);
							ConversationActivity.this.mConversationFragment
									.reInit(getSelectedConversation());
						}
						swipedConversation = null;
						listView.setSelectionFromTop(index + (listView.getChildCount() < position ? 1 : 0), top);
					}

					@Override
					public void discard() {
						if (!swipedConversation.isRead()
								&& swipedConversation.getMode() == Conversation.MODE_SINGLE) {
							swipedConversation = null;
							return;
						}
						endConversation(swipedConversation, false, false);
						swipedConversation = null;
					}

					@Override
					public String getTitle() {
						if (swipedConversation.getMode() == Conversation.MODE_MULTI) {
							return getResources().getString(R.string.title_undo_swipe_out_muc);
						} else {
							return getResources().getString(R.string.title_undo_swipe_out_conversation);
						}
					}
				};
			}
		});
		listView.enableSwipeToDismiss();
		listView.setSwipingLayout(R.id.swipeable_item);
		listView.setUndoStyle(EnhancedListView.UndoStyle.SINGLE_POPUP);
		listView.setUndoHideDelay(5000);
		listView.setRequireTouchBeforeDismiss(false);

		mContentView = findViewById(R.id.content_view_spl);
		if (mContentView == null) {
			mContentView = findViewById(R.id.content_view_ll);
		}
		final ActionBar actionBar = getActionBar();
		final View conversationListPane = findViewById(R.id.conversation_list_pane);
		// Only Android 15+ (API 35, targetSdk 35+'s forced edge-to-edge
		// threshold — see the fitsSystemWindows comment in
		// XmppActivity.onCreate()) fails to reserve the ActionBar's own
		// space automatically for this SlidingPaneLayout-rooted screen. On
		// older OS versions the classic content-frame reservation already
		// works correctly here (exactly as it always did, long before this
		// tab row existed) — confirmed live: on a real device running an
		// older Android version, applying this compensating padding on top
		// of already-correct native positioning produced a visible white
		// gap between the ActionBar and the tab row, the opposite problem
		// from the one this padding fixes on newer OS versions. Gating by
		// SDK_INT avoids fighting the framework on devices where it's
		// already doing the right thing.
		if (actionBar != null && conversationListPane != null && Build.VERSION.SDK_INT >= 35) {
			// Deliberately never removes this listener (unlike a typical
			// one-shot "wait for the first non-zero measurement" use of
			// OnGlobalLayoutListener): re-checking on every layout pass and
			// only touching padding when the measured height actually
			// changed keeps this correct regardless of when the ActionBar
			// settles, without looping (a no-op setPadding call doesn't
			// trigger another layout pass).
			getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
				@Override
				public void onGlobalLayout() {
					final int height = actionBar.getHeight();
					if (height > 0 && conversationListPane.getPaddingTop() != height) {
						conversationListPane.setPadding(
								conversationListPane.getPaddingLeft(),
								height,
								conversationListPane.getPaddingRight(),
								conversationListPane.getPaddingBottom());
					}
				}
			});
		}
		if (mContentView instanceof SlidingPaneLayout) {
			SlidingPaneLayout mSlidingPaneLayout = (SlidingPaneLayout) mContentView;
			mSlidingPaneLayout.setShadowResource(R.drawable.es_slidingpane_shadow);
			mSlidingPaneLayout.setSliderFadeColor(0);
			mSlidingPaneLayout.setPanelSlideListener(new PanelSlideListener() {

				// NOTE: onPanelOpened/onPanelClosed bodies are swapped from what their
				// names might suggest, matching AndroidX SlidingPaneLayout's actual
				// semantics: "opened" means the DETAIL (conversation) pane is fully
				// visible, "closed" means the DETAIL pane is closed (list/overview
				// pane visible instead) — the opposite of the old support-v4 behavior
				// this code was originally written against.
				@Override
				public void onPanelOpened(View arg0) {
					mShouldPanelBeOpen.set(false);
					listView.discardUndo();
					openConversation();
				}

				@Override
				public void onPanelClosed(View arg0) {
					mShouldPanelBeOpen.set(true);
					updateActionBarTitle();
					invalidateOptionsMenu();
					hideKeyboard();
					if (xmppConnectionServiceBound) {
						xmppConnectionService.getNotificationService().setOpenConversation(null);
					}
					closeContextMenu();
				}

				@Override
				public void onPanelSlide(View arg0, float arg1) {
					// TODO Auto-generated method stub

				}
			});
		}
	}

	@Override
	public void switchToConversation(Conversation conversation) {
		setSelectedConversation(conversation);
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				ConversationActivity.this.mConversationFragment.reInit(getSelectedConversation());
				// AndroidX SlidingPaneLayout defaults to showing the list/overview pane on
				// first layout (the old support-v4 version defaulted to the detail pane) —
				// explicitly show the detail pane rather than relying on that default.
				hideConversationsOverview();
				openConversation();
			}
		});
	}

	private void updateActionBarTitle() {
		updateActionBarTitle(isConversationsOverviewHideable() && !isConversationsOverviewVisable());
	}

	private void updateActionBarTitle(boolean titleShouldBeName) {
		final ActionBar ab = getActionBar();
		final Conversation conversation = getSelectedConversation();
		if (ab != null) {
			if (titleShouldBeName && conversation != null) {
				if ((ab.getDisplayOptions() & ActionBar.DISPLAY_HOME_AS_UP) != ActionBar.DISPLAY_HOME_AS_UP) {
					ab.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_TITLE);
				}
				if (conversation.getMode() == Conversation.MODE_SINGLE || useSubjectToIdentifyConference()) {
					ab.setTitle(conversation.getName());
				} else {
					ab.setTitle(conversation.getJid().toBareJid().toString());
				}
			} else {
				if ((ab.getDisplayOptions() & ActionBar.DISPLAY_HOME_AS_UP) == ActionBar.DISPLAY_HOME_AS_UP) {
					ab.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE);
				}
				ab.setTitle(R.string.app_name);
			}
		}
	}

	private void openConversation() {
		this.updateActionBarTitle();
		this.invalidateOptionsMenu();
		if (xmppConnectionServiceBound) {
			final Conversation conversation = getSelectedConversation();
			xmppConnectionService.getNotificationService().setOpenConversation(conversation);
			sendReadMarkerIfNecessary(conversation);
		}
		listAdapter.notifyDataSetChanged();
	}

	public void sendReadMarkerIfNecessary(final Conversation conversation) {
		if (!mActivityPaused && !mUnprocessedNewIntent && conversation != null) {
			xmppConnectionService.sendReadMarker(conversation);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.conversations, menu);
		final MenuItem menuSecure = menu.findItem(R.id.action_security);
		final MenuItem menuArchive = menu.findItem(R.id.action_archive);
		final MenuItem menuMucDetails = menu.findItem(R.id.action_muc_details);
		final MenuItem menuContactDetails = menu.findItem(R.id.action_contact_details);
		final MenuItem menuAttach = menu.findItem(R.id.action_attach_file);
		final MenuItem menuCall = menu.findItem(R.id.action_call);
		final MenuItem menuVideoCall = menu.findItem(R.id.action_video_call);
		final MenuItem menuClearHistory = menu.findItem(R.id.action_clear_history);
		final MenuItem menuAdd = menu.findItem(R.id.action_add);
		final MenuItem menuInviteContact = menu.findItem(R.id.action_invite);
		final MenuItem menuMute = menu.findItem(R.id.action_mute);
		final MenuItem menuUnmute = menu.findItem(R.id.action_unmute);

		if (isConversationsOverviewVisable() && isConversationsOverviewHideable()) {
			menuArchive.setVisible(false);
			menuMucDetails.setVisible(false);
			menuContactDetails.setVisible(false);
			menuSecure.setVisible(false);
			menuInviteContact.setVisible(false);
			menuAttach.setVisible(false);
			menuClearHistory.setVisible(false);
			menuMute.setVisible(false);
			menuUnmute.setVisible(false);
			menuCall.setVisible(false);
			menuVideoCall.setVisible(false);
		} else {
			menuAdd.setVisible(!isConversationsOverviewHideable());
			if (this.getSelectedConversation() != null) {
				final boolean canCall = this.getSelectedConversation().getMode() == Conversation.MODE_SINGLE
						&& !this.getSelectedConversation().withSelf();
				menuCall.setVisible(canCall);
				menuVideoCall.setVisible(canCall);
				if (this.getSelectedConversation().getNextEncryption() != Message.ENCRYPTION_NONE) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
						menuSecure.setIcon(R.drawable.ic_lock_white_24dp);
					} else {
						menuSecure.setIcon(R.drawable.ic_action_secure);
					}
				}
				if (this.getSelectedConversation().getMode() == Conversation.MODE_MULTI) {
					menuContactDetails.setVisible(false);
					menuAttach.setVisible(getSelectedConversation().getAccount().httpUploadAvailable() && getSelectedConversation().getMucOptions().participating());
					menuInviteContact.setVisible(getSelectedConversation().getMucOptions().canInvite());
					menuSecure.setVisible((Config.supportOpenPgp() || Config.supportOmemo()) && Config.multipleEncryptionChoices()); //only if pgp is supported we have a choice
				} else {
					menuContactDetails.setVisible(!this.getSelectedConversation().withSelf());
					menuMucDetails.setVisible(false);
					menuSecure.setVisible(Config.multipleEncryptionChoices());
					menuInviteContact.setVisible(xmppConnectionService != null && xmppConnectionService.findConferenceServer(getSelectedConversation().getAccount()) != null);
				}
				if (this.getSelectedConversation().isMuted()) {
					menuMute.setVisible(false);
				} else {
					menuUnmute.setVisible(false);
				}
			}
		}
		if (Config.supportOmemo()) {
			new Handler().post(new Runnable() {
				@Override
				public void run() {
					View view = findViewById(R.id.action_security);
					if (view != null) {
						view.setOnLongClickListener(new View.OnLongClickListener() {
							@Override
							public boolean onLongClick(View v) {
								return quickOmemoDebugger(getSelectedConversation());
							}
						});
					}
				}
			});
		}
		return super.onCreateOptionsMenu(menu);
	}

	private boolean quickOmemoDebugger(Conversation c) {
		if (c != null) {
			boolean single = c.getMode() == Conversation.MODE_SINGLE;
			AxolotlService axolotlService = c.getAccount().getAxolotlService();
			Pair<AxolotlService.AxolotlCapability,Jid> capabilityJidPair = axolotlService.isConversationAxolotlCapableDetailed(c);
			switch (capabilityJidPair.first) {
				case MISSING_PRESENCE:
					Toast.makeText(ConversationActivity.this,single ? getString(R.string.missing_presence_subscription) : getString(R.string.missing_presence_subscription_with_x,capabilityJidPair.second.toBareJid().toString()),Toast.LENGTH_SHORT).show();
					return true;
				case MISSING_KEYS:
					Toast.makeText(ConversationActivity.this,single ? getString(R.string.missing_omemo_keys) : getString(R.string.missing_keys_from_x,capabilityJidPair.second.toBareJid().toString()),Toast.LENGTH_SHORT).show();
					return true;
				case WRONG_CONFIGURATION:
					Toast.makeText(ConversationActivity.this,R.string.wrong_conference_configuration, Toast.LENGTH_SHORT).show();
					return true;
				case NO_MEMBERS:
					Toast.makeText(ConversationActivity.this,R.string.this_conference_has_no_members, Toast.LENGTH_SHORT).show();
					return true;
			}
		}
		return false;
	}

	protected void selectPresenceToAttachFile(final int attachmentChoice, final int encryption) {
		final Conversation conversation = getSelectedConversation();
		final Account account = conversation.getAccount();
		final OnPresenceSelected callback = new OnPresenceSelected() {

			@Override
			public void onPresenceSelected() {
				if (attachmentChoice == ATTACHMENT_CHOICE_RECORD_VOICE) {
					startVoiceMessageRecording(conversation);
					return;
				}
				Intent intent = new Intent();
				boolean chooser = false;
				String fallbackPackageId = null;
				switch (attachmentChoice) {
					case ATTACHMENT_CHOICE_CHOOSE_IMAGE:
						intent.setAction(Intent.ACTION_GET_CONTENT);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
							intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
						}
						intent.setType("image/*");
						chooser = true;
						break;
					case ATTACHMENT_CHOICE_TAKE_PHOTO:
						Uri uri = xmppConnectionService.getFileBackend().getTakePhotoUri();
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
						intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						intent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
						intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
						mPendingImageUris.clear();
						mPendingImageUris.add(uri);
						break;
					case ATTACHMENT_CHOICE_CHOOSE_FILE:
						chooser = true;
						intent.setType("*/*");
						intent.addCategory(Intent.CATEGORY_OPENABLE);
						intent.setAction(Intent.ACTION_GET_CONTENT);
						break;
					case ATTACHMENT_CHOICE_LOCATION:
						intent.setAction("pl.rikitiki.im.location.request");
						fallbackPackageId = "pl.rikitiki.im.sharelocation";
						break;
				}
				if (intent.resolveActivity(getPackageManager()) != null) {
					if (chooser) {
						startActivityForResult(
								Intent.createChooser(intent, getString(R.string.perform_action_with)),
								attachmentChoice);
					} else {
						startActivityForResult(intent, attachmentChoice);
					}
				} else if (fallbackPackageId != null) {
					startActivity(getInstallApkIntent(fallbackPackageId));
				}
			}
		};
		if ((account.httpUploadAvailable() || attachmentChoice == ATTACHMENT_CHOICE_LOCATION) && encryption != Message.ENCRYPTION_OTR) {
			conversation.setNextCounterpart(null);
			callback.onPresenceSelected();
		} else {
			selectPresence(conversation, callback);
		}
	}

	private Intent getInstallApkIntent(final String packageId) {
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse("market://details?id=" + packageId));
		if (intent.resolveActivity(getPackageManager()) != null) {
			return intent;
		} else {
			intent.setData(Uri.parse("http://play.google.com/store/apps/details?id=" + packageId));
			return intent;
		}
	}

	public void attachFile(final int attachmentChoice) {
		if (attachmentChoice != ATTACHMENT_CHOICE_LOCATION) {
			if (!Config.ONLY_INTERNAL_STORAGE && !hasStoragePermission(attachmentChoice)) {
				return;
			}
		}
		switch (attachmentChoice) {
			case ATTACHMENT_CHOICE_LOCATION:
				getPreferences().edit().putString("recently_used_quick_action", "location").apply();
				break;
			case ATTACHMENT_CHOICE_RECORD_VOICE:
				getPreferences().edit().putString("recently_used_quick_action", "voice").apply();
				break;
			case ATTACHMENT_CHOICE_TAKE_PHOTO:
				getPreferences().edit().putString("recently_used_quick_action", "photo").apply();
				break;
			case ATTACHMENT_CHOICE_CHOOSE_IMAGE:
				getPreferences().edit().putString("recently_used_quick_action", "picture").apply();
				break;
		}
		final Conversation conversation = getSelectedConversation();
		final int encryption = conversation.getNextEncryption();
		final int mode = conversation.getMode();
		if (encryption == Message.ENCRYPTION_PGP) {
			if (hasPgp()) {
				if (mode == Conversation.MODE_SINGLE && conversation.getContact().getPgpKeyId() != 0) {
					xmppConnectionService.getPgpEngine().hasKey(
							conversation.getContact(),
							new UiCallback<Contact>() {

								@Override
								public void userInputRequried(PendingIntent pi, Contact contact) {
									ConversationActivity.this.runIntent(pi, attachmentChoice);
								}

								@Override
								public void success(Contact contact) {
									selectPresenceToAttachFile(attachmentChoice, encryption);
								}

								@Override
								public void error(int error, Contact contact) {
									replaceToast(getString(error));
								}
							});
				} else if (mode == Conversation.MODE_MULTI && conversation.getMucOptions().pgpKeysInUse()) {
					if (!conversation.getMucOptions().everybodyHasKeys()) {
						Toast warning = Toast
								.makeText(this,
										R.string.missing_public_keys,
										Toast.LENGTH_LONG);
						warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
						warning.show();
					}
					selectPresenceToAttachFile(attachmentChoice, encryption);
				} else {
					final ConversationFragment fragment = (ConversationFragment) getFragmentManager()
							.findFragmentByTag("conversation");
					if (fragment != null) {
						fragment.showNoPGPKeyDialog(false,
								new OnClickListener() {

									@Override
									public void onClick(DialogInterface dialog,
														int which) {
										conversation.setNextEncryption(Message.ENCRYPTION_NONE);
										xmppConnectionService.updateConversation(conversation);
										selectPresenceToAttachFile(attachmentChoice, Message.ENCRYPTION_NONE);
									}
								});
					}
				}
			} else {
				showInstallPgpDialog();
			}
		} else {
			if (encryption != Message.ENCRYPTION_AXOLOTL || !trustKeysIfNeeded(REQUEST_TRUST_KEYS_MENU, attachmentChoice)) {
				selectPresenceToAttachFile(attachmentChoice, encryption);
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
			return;
		}
		if (grantResults.length > 0)
			if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (requestCode == REQUEST_START_DOWNLOAD) {
					if (this.mPendingDownloadableMessage != null) {
						startDownloadable(this.mPendingDownloadableMessage);
					}
				} else if (requestCode == REQUEST_CALL) {
					placeCall(getSelectedConversation(), false);
				} else if (requestCode == REQUEST_CALL_VIDEO) {
					placeCall(getSelectedConversation(), this.mPendingCallIsVideo);
				} else if (requestCode == REQUEST_RECORD_VOICE_MESSAGE) {
					startVoiceMessageRecording(getSelectedConversation());
				} else {
					attachFile(requestCode);
				}
			} else if (requestCode == REQUEST_CALL) {
				Toast.makeText(this, R.string.no_microphone_permission, Toast.LENGTH_SHORT).show();
			} else if (requestCode == REQUEST_CALL_VIDEO) {
				final boolean deniedCamera = permissions.length > 0 && Manifest.permission.CAMERA.equals(permissions[0]);
				Toast.makeText(this, deniedCamera ? R.string.no_camera_permission : R.string.no_microphone_permission, Toast.LENGTH_SHORT).show();
			} else if (requestCode == REQUEST_RECORD_VOICE_MESSAGE) {
				Toast.makeText(this, R.string.no_microphone_permission_voice_message, Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
			}
	}

	public void placeCall(final Conversation conversation) {
		placeCall(conversation, false);
	}

	public void placeCall(final Conversation conversation, final boolean isVideo) {
		if (conversation == null) {
			return;
		}
		this.mPendingCallIsVideo = isVideo;
		if (!hasRecordAudioPermission(isVideo ? REQUEST_CALL_VIDEO : REQUEST_CALL)) {
			return;
		}
		if (isVideo && !hasCameraPermission(REQUEST_CALL_VIDEO)) {
			return;
		}
		xmppConnectionService.getJingleConnectionManager()
				.createOutgoingRtpConnection(conversation.getAccount(), conversation.getJid(), isVideo);
		startActivity(new Intent(this, RtpSessionActivity.class));
	}

	public void startDownloadable(Message message) {
		if (!Config.ONLY_INTERNAL_STORAGE && !hasStoragePermission(ConversationActivity.REQUEST_START_DOWNLOAD)) {
			this.mPendingDownloadableMessage = message;
			return;
		}
		Transferable transferable = message.getTransferable();
		if (transferable != null) {
			if (!transferable.start()) {
				Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
			}
		} else if (message.treatAsDownloadable()) {
			xmppConnectionService.getHttpConnectionManager().createNewDownloadConnection(message, true);
		}
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			showConversationsOverview();
			return true;
		} else if (item.getItemId() == R.id.action_add) {
			if (mConversationsTabMode == Conversation.MODE_MULTI) {
				showCreateConferenceDialog();
			} else {
				startActivity(new Intent(this, PhoneContactsActivity.class));
			}
			return true;
		} else if (getSelectedConversation() != null) {
			final int id = item.getItemId();
			if (id == R.id.action_attach_file) {
				attachFileDialog();
			} else if (id == R.id.action_call) {
				placeCall(getSelectedConversation());
			} else if (id == R.id.action_video_call) {
				placeCall(getSelectedConversation(), true);
			} else if (id == R.id.action_archive) {
				this.endConversation(getSelectedConversation());
			} else if (id == R.id.action_contact_details) {
				switchToContactDetails(getSelectedConversation().getContact());
			} else if (id == R.id.action_muc_details) {
				Intent intent = new Intent(this,
						ConferenceDetailsActivity.class);
				intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
				intent.putExtra("uuid", getSelectedConversation().getUuid());
				startActivity(intent);
			} else if (id == R.id.action_invite) {
				inviteToConversation(getSelectedConversation());
			} else if (id == R.id.action_security) {
				selectEncryptionDialog(getSelectedConversation());
			} else if (id == R.id.action_clear_history) {
				clearHistoryDialog(getSelectedConversation());
			} else if (id == R.id.action_mute) {
				muteConversationDialog(getSelectedConversation());
			} else if (id == R.id.action_unmute) {
				unmuteConversation(getSelectedConversation());
			} else if (id == R.id.action_block) {
				BlockContactDialog.show(this, getSelectedConversation());
			} else if (id == R.id.action_unblock) {
				BlockContactDialog.show(this, getSelectedConversation());
			}
			return super.onOptionsItemSelected(item);
		} else {
			return super.onOptionsItemSelected(item);
		}
	}

	public void endConversation(Conversation conversation) {
		endConversation(conversation, true, true);
	}

	public void endConversation(Conversation conversation, boolean showOverview, boolean reinit) {
		if (showOverview) {
			showConversationsOverview();
		}
		xmppConnectionService.archiveConversation(conversation);
		if (reinit) {
			if (conversationList.size() > 0) {
				setSelectedConversation(conversationList.get(0));
				this.mConversationFragment.reInit(getSelectedConversation());
			} else {
				setSelectedConversation(null);
				if (mRedirected.compareAndSet(false, true)) {
					Intent intent = new Intent(this, PhoneContactsActivity.class);
					intent.putExtra("init", true);
					startActivity(intent);
					finish();
				}
			}
		}
	}

	@SuppressLint("InflateParams")
	protected void clearHistoryDialog(final Conversation conversation) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(getString(R.string.clear_conversation_history));
		View dialogView = getLayoutInflater().inflate(
				R.layout.dialog_clear_history, null);
		final CheckBox endConversationCheckBox = (CheckBox) dialogView
				.findViewById(R.id.end_conversation_checkbox);
		builder.setView(dialogView);
		builder.setNegativeButton(getString(R.string.cancel), null);
		builder.setPositiveButton(getString(R.string.delete_messages),
				new OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						ConversationActivity.this.xmppConnectionService.clearConversationHistory(conversation);
						if (endConversationCheckBox.isChecked()) {
							endConversation(conversation);
						} else {
							updateConversationList();
							ConversationActivity.this.mConversationFragment.updateMessages();
						}
					}
				});
		builder.create().show();
	}

	protected void attachFileDialog() {
		View menuAttachFile = findViewById(R.id.action_attach_file);
		if (menuAttachFile == null) {
			return;
		}
		PopupMenu attachFilePopup = new PopupMenu(this, menuAttachFile);
		attachFilePopup.inflate(R.menu.attachment_choices);
		if (new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).resolveActivity(getPackageManager()) == null) {
			attachFilePopup.getMenu().findItem(R.id.attach_record_voice).setVisible(false);
		}
		if (new Intent("pl.rikitiki.im.location.request").resolveActivity(getPackageManager()) == null) {
			attachFilePopup.getMenu().findItem(R.id.attach_location).setVisible(false);
		}
		attachFilePopup.setOnMenuItemClickListener(new OnMenuItemClickListener() {

			@Override
			public boolean onMenuItemClick(MenuItem item) {
				final int id = item.getItemId();
				if (id == R.id.attach_choose_picture) {
					attachFile(ATTACHMENT_CHOICE_CHOOSE_IMAGE);
				} else if (id == R.id.attach_take_picture) {
					attachFile(ATTACHMENT_CHOICE_TAKE_PHOTO);
				} else if (id == R.id.attach_choose_file) {
					attachFile(ATTACHMENT_CHOICE_CHOOSE_FILE);
				} else if (id == R.id.attach_record_voice) {
					attachFile(ATTACHMENT_CHOICE_RECORD_VOICE);
				} else if (id == R.id.attach_location) {
					attachFile(ATTACHMENT_CHOICE_LOCATION);
				}
				return false;
			}
		});
		UIHelper.showIconsInPopup(attachFilePopup);
		attachFilePopup.show();
	}

	public void verifyOtrSessionDialog(final Conversation conversation, View view) {
		if (!conversation.hasValidOtrSession() || conversation.getOtrSession().getSessionStatus() != SessionStatus.ENCRYPTED) {
			Toast.makeText(this, R.string.otr_session_not_started, Toast.LENGTH_LONG).show();
			return;
		}
		if (view == null) {
			return;
		}
		PopupMenu popup = new PopupMenu(this, view);
		popup.inflate(R.menu.verification_choices);
		popup.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem menuItem) {
				Intent intent = new Intent(ConversationActivity.this, VerifyOTRActivity.class);
				intent.setAction(VerifyOTRActivity.ACTION_VERIFY_CONTACT);
				intent.putExtra("contact", conversation.getContact().getJid().toBareJid().toString());
				intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().toBareJid().toString());
				final int id = menuItem.getItemId();
				if (id == R.id.scan_fingerprint) {
					intent.putExtra("mode", VerifyOTRActivity.MODE_SCAN_FINGERPRINT);
				} else if (id == R.id.ask_question) {
					intent.putExtra("mode", VerifyOTRActivity.MODE_ASK_QUESTION);
				} else if (id == R.id.manual_verification) {
					intent.putExtra("mode", VerifyOTRActivity.MODE_MANUAL_VERIFICATION);
				}
				startActivity(intent);
				return true;
			}
		});
		popup.show();
	}

	protected void selectEncryptionDialog(final Conversation conversation) {
		View menuItemView = findViewById(R.id.action_security);
		if (menuItemView == null) {
			return;
		}
		PopupMenu popup = new PopupMenu(this, menuItemView);
		final ConversationFragment fragment = (ConversationFragment) getFragmentManager()
				.findFragmentByTag("conversation");
		if (fragment != null) {
			popup.setOnMenuItemClickListener(new OnMenuItemClickListener() {

				@Override
				public boolean onMenuItemClick(MenuItem item) {
					final int id = item.getItemId();
					if (id == R.id.encryption_choice_none) {
						conversation.setNextEncryption(Message.ENCRYPTION_NONE);
						item.setChecked(true);
					} else if (id == R.id.encryption_choice_otr) {
						conversation.setNextEncryption(Message.ENCRYPTION_OTR);
						item.setChecked(true);
					} else if (id == R.id.encryption_choice_pgp) {
						if (hasPgp()) {
							if (conversation.getAccount().getPgpSignature() != null) {
								conversation.setNextEncryption(Message.ENCRYPTION_PGP);
								item.setChecked(true);
							} else {
								announcePgp(conversation.getAccount(), conversation, onOpenPGPKeyPublished);
							}
						} else {
							showInstallPgpDialog();
						}
					} else if (id == R.id.encryption_choice_axolotl) {
						Log.d(Config.LOGTAG, AxolotlService.getLogprefix(conversation.getAccount())
								+ "Enabled axolotl for Contact " + conversation.getContact().getJid());
						conversation.setNextEncryption(Message.ENCRYPTION_AXOLOTL);
						item.setChecked(true);
					} else {
						conversation.setNextEncryption(Message.ENCRYPTION_NONE);
					}
					xmppConnectionService.updateConversation(conversation);
					fragment.updateChatMsgHint();
					invalidateOptionsMenu();
					refreshUi();
					return true;
				}
			});
			popup.inflate(R.menu.encryption_choices);
			MenuItem otr = popup.getMenu().findItem(R.id.encryption_choice_otr);
			MenuItem none = popup.getMenu().findItem(R.id.encryption_choice_none);
			MenuItem pgp = popup.getMenu().findItem(R.id.encryption_choice_pgp);
			MenuItem axolotl = popup.getMenu().findItem(R.id.encryption_choice_axolotl);
			pgp.setVisible(Config.supportOpenPgp());
			none.setVisible(Config.supportUnencrypted() || conversation.getMode() == Conversation.MODE_MULTI);
			otr.setVisible(Config.supportOtr());
			axolotl.setVisible(Config.supportOmemo());
			if (conversation.getMode() == Conversation.MODE_MULTI) {
				otr.setVisible(false);
			}
			if (!conversation.getAccount().getAxolotlService().isConversationAxolotlCapable(conversation)) {
				axolotl.setEnabled(false);
			}
			switch (conversation.getNextEncryption()) {
				case Message.ENCRYPTION_NONE:
					none.setChecked(true);
					break;
				case Message.ENCRYPTION_OTR:
					otr.setChecked(true);
					break;
				case Message.ENCRYPTION_PGP:
					pgp.setChecked(true);
					break;
				case Message.ENCRYPTION_AXOLOTL:
					axolotl.setChecked(true);
					break;
				default:
					none.setChecked(true);
					break;
			}
			popup.show();
		}
	}

	protected void muteConversationDialog(final Conversation conversation) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.disable_notifications);
		final int[] durations = getResources().getIntArray(R.array.mute_options_durations);
		builder.setItems(R.array.mute_options_descriptions,
				new OnClickListener() {

					@Override
					public void onClick(final DialogInterface dialog, final int which) {
						final long till;
						if (durations[which] == -1) {
							till = Long.MAX_VALUE;
						} else {
							till = System.currentTimeMillis() + (durations[which] * 1000);
						}
						conversation.setMutedTill(till);
						ConversationActivity.this.xmppConnectionService.updateConversation(conversation);
						updateConversationList();
						ConversationActivity.this.mConversationFragment.updateMessages();
						invalidateOptionsMenu();
					}
				});
		builder.create().show();
	}

	public void unmuteConversation(final Conversation conversation) {
		conversation.setMutedTill(0);
		this.xmppConnectionService.updateConversation(conversation);
		updateConversationList();
		ConversationActivity.this.mConversationFragment.updateMessages();
		invalidateOptionsMenu();
	}

	@Override
	public void onBackPressed() {
		if (!isConversationsOverviewVisable()) {
			showConversationsOverview();
		} else {
			super.onBackPressed();
		}
	}

	@Override
	public boolean onKeyUp(int key, KeyEvent event) {
		int rotation = getWindowManager().getDefaultDisplay().getRotation();
		final int upKey;
		final int downKey;
		switch (rotation) {
			case Surface.ROTATION_90:
				upKey = KeyEvent.KEYCODE_DPAD_LEFT;
				downKey = KeyEvent.KEYCODE_DPAD_RIGHT;
				break;
			case Surface.ROTATION_180:
				upKey = KeyEvent.KEYCODE_DPAD_DOWN;
				downKey = KeyEvent.KEYCODE_DPAD_UP;
				break;
			case Surface.ROTATION_270:
				upKey = KeyEvent.KEYCODE_DPAD_RIGHT;
				downKey = KeyEvent.KEYCODE_DPAD_LEFT;
				break;
			case Surface.ROTATION_0:
			default:
				upKey = KeyEvent.KEYCODE_DPAD_UP;
				downKey = KeyEvent.KEYCODE_DPAD_DOWN;
		}
		final boolean modifier = event.isCtrlPressed() || (event.getMetaState() & KeyEvent.META_ALT_LEFT_ON) != 0;
		if (modifier && key == KeyEvent.KEYCODE_TAB && isConversationsOverviewHideable()) {
			toggleConversationsOverview();
			return true;
		} else if (modifier && key == KeyEvent.KEYCODE_SPACE) {
			startActivity(new Intent(this, PhoneContactsActivity.class));
			return true;
		} else if (modifier && key == downKey) {
			if (isConversationsOverviewHideable() && !isConversationsOverviewVisable()) {
				showConversationsOverview();
				;
			}
			return selectDownConversation();
		} else if (modifier && key == upKey) {
			if (isConversationsOverviewHideable() && !isConversationsOverviewVisable()) {
				showConversationsOverview();
			}
			return selectUpConversation();
		} else if (modifier && key == KeyEvent.KEYCODE_1) {
			return openConversationByIndex(0);
		} else if (modifier && key == KeyEvent.KEYCODE_2) {
			return openConversationByIndex(1);
		} else if (modifier && key == KeyEvent.KEYCODE_3) {
			return openConversationByIndex(2);
		} else if (modifier && key == KeyEvent.KEYCODE_4) {
			return openConversationByIndex(3);
		} else if (modifier && key == KeyEvent.KEYCODE_5) {
			return openConversationByIndex(4);
		} else if (modifier && key == KeyEvent.KEYCODE_6) {
			return openConversationByIndex(5);
		} else if (modifier && key == KeyEvent.KEYCODE_7) {
			return openConversationByIndex(6);
		} else if (modifier && key == KeyEvent.KEYCODE_8) {
			return openConversationByIndex(7);
		} else if (modifier && key == KeyEvent.KEYCODE_9) {
			return openConversationByIndex(8);
		} else if (modifier && key == KeyEvent.KEYCODE_0) {
			return openConversationByIndex(9);
		} else {
			return super.onKeyUp(key, event);
		}
	}

	private void toggleConversationsOverview() {
		if (isConversationsOverviewVisable()) {
			hideConversationsOverview();
			if (mConversationFragment != null) {
				mConversationFragment.setFocusOnInputField();
			}
		} else {
			showConversationsOverview();
		}
	}

	private boolean selectUpConversation() {
		if (this.mSelectedConversation != null) {
			int index = this.conversationList.indexOf(this.mSelectedConversation);
			if (index > 0) {
				return openConversationByIndex(index - 1);
			}
		}
		return false;
	}

	private boolean selectDownConversation() {
		if (this.mSelectedConversation != null) {
			int index = this.conversationList.indexOf(this.mSelectedConversation);
			if (index != -1 && index < this.conversationList.size() - 1) {
				return openConversationByIndex(index + 1);
			}
		}
		return false;
	}

	private boolean openConversationByIndex(int index) {
		try {
			this.conversationWasSelectedByKeyboard = true;
			setSelectedConversation(this.conversationList.get(index));
			this.mConversationFragment.reInit(getSelectedConversation());
			if (index > listView.getLastVisiblePosition() - 1 || index < listView.getFirstVisiblePosition() + 1) {
				this.listView.setSelection(index);
			}
			openConversation();
			return true;
		} catch (IndexOutOfBoundsException e) {
			return false;
		}
	}

	@Override
	protected void onNewIntent(final Intent intent) {
		if (intent != null && ACTION_VIEW_CONVERSATION.equals(intent.getAction())) {
			mOpenConversation = null;
			mUnprocessedNewIntent = true;
			if (xmppConnectionServiceBound) {
				handleViewConversationIntent(intent);
				intent.setAction(Intent.ACTION_MAIN);
			} else {
				setIntent(intent);
			}
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		this.mRedirected.set(false);
		if (this.xmppConnectionServiceBound) {
			this.onBackendConnected();
		}
		if (conversationList.size() >= 1) {
			this.onConversationUpdate();
		}
	}

	@Override
	public void onPause() {
		listView.discardUndo();
		super.onPause();
		this.mActivityPaused = true;
	}

	@Override
	public void onResume() {
		super.onResume();
		final int theme = findTheme();
		final boolean usingEnterKey = usingEnterKey();
		if (this.mTheme != theme || usingEnterKey != mUsingEnterKey) {
			recreate();
		}
		this.mActivityPaused = false;


		if (!isConversationsOverviewVisable() || !isConversationsOverviewHideable()) {
			sendReadMarkerIfNecessary(getSelectedConversation());
		}

	}

	@Override
	public void onSaveInstanceState(final Bundle savedInstanceState) {
		Conversation conversation = getSelectedConversation();
		if (conversation != null) {
			savedInstanceState.putString(STATE_OPEN_CONVERSATION, conversation.getUuid());
			Pair<Integer,Integer> scrollPosition = mConversationFragment.getScrollPosition();
			if (scrollPosition != null) {
				savedInstanceState.putInt(STATE_FIRST_VISIBLE, scrollPosition.first);
				savedInstanceState.putInt(STATE_OFFSET_FROM_TOP, scrollPosition.second);
			}
		} else {
			savedInstanceState.remove(STATE_OPEN_CONVERSATION);
		}
		savedInstanceState.putBoolean(STATE_PANEL_OPEN, isConversationsOverviewVisable());
		if (this.mPendingImageUris.size() >= 1) {
			Log.d(Config.LOGTAG,"ConversationsActivity.onSaveInstanceState() - saving pending image uri");
			savedInstanceState.putString(STATE_PENDING_URI, this.mPendingImageUris.get(0).toString());
		} else {
			savedInstanceState.remove(STATE_PENDING_URI);
		}
		super.onSaveInstanceState(savedInstanceState);
	}

	private void clearPending() {
		mPendingImageUris.clear();
		mPendingFileUris.clear();
		mPendingGeoUri = null;
		mPostponedActivityResult = null;
	}

	private void redirectToStartConversationActivity() {
		Account pendingAccount = xmppConnectionService.getPendingAccount();
		if (pendingAccount == null) {
			Intent startConversationActivity = new Intent(this, PhoneContactsActivity.class);
			startConversationActivity.putExtra("init", true);
			startActivity(startConversationActivity);
		} else {
			switchToAccount(pendingAccount, true);
		}
		finish();
	}

	@Override
	void onBackendConnected() {
		this.xmppConnectionService.getNotificationService().setIsInForeground(true);
		updateConversationList();

		if (mPendingConferenceInvite != null) {
			if (mPendingConferenceInvite.execute(this)) {
				mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
				mToast.show();
			}
			mPendingConferenceInvite = null;
		}

		final Intent intent = getIntent();

		if (xmppConnectionService.getAccounts().size() == 0) {
			if (mRedirected.compareAndSet(false, true)) {
				if (Config.X509_VERIFICATION) {
					startActivity(new Intent(this, ManageAccountActivity.class));
				} else if (Config.MAGIC_CREATE_DOMAIN != null) {
					startActivity(new Intent(this, WelcomeActivity.class));
				} else {
					Intent editAccount = new Intent(this, EditAccountActivity.class);
					editAccount.putExtra("init",true);
					startActivity(editAccount);
				}
				finish();
			}
		} else if (conversationList.size() <= 0 && contactsWithoutConversation.isEmpty()
				&& xmppConnectionService.getConversations().isEmpty()) {
			// The current tab (Chats/Rooms) being empty isn't enough on its
			// own — only redirect to "start a conversation" when there are
			// truly none at all, on either tab.
			if (mRedirected.compareAndSet(false, true)) {
				redirectToStartConversationActivity();
			}
		} else if (selectConversationByUuid(mOpenConversation)) {
			if (mPanelOpen) {
				showConversationsOverview();
			} else {
				if (isConversationsOverviewHideable()) {
					openConversation();
					updateActionBarTitle(true);
				}
			}
			if (this.mConversationFragment.reInit(getSelectedConversation())) {
				Log.d(Config.LOGTAG,"setting scroll position on fragment");
				this.mConversationFragment.setScrollPosition(mScrollPosition);
			}
			mOpenConversation = null;
		} else if (intent != null && ACTION_VIEW_CONVERSATION.equals(intent.getAction())) {
			clearPending();
			handleViewConversationIntent(intent);
			intent.setAction(Intent.ACTION_MAIN);
		} else if (getSelectedConversation() == null) {
			if (conversationList.size() > 0) {
				reInitLatestConversation();
			} else {
				showConversationsOverview();
			}
		} else {
			this.mConversationFragment.messageListAdapter.updatePreferences();
			this.mConversationFragment.messagesView.invalidateViews();
			this.mConversationFragment.setupIme();
		}

		if (this.mPostponedActivityResult != null) {
			this.onActivityResult(mPostponedActivityResult.first, RESULT_OK, mPostponedActivityResult.second);
		}

		final boolean stopping = isStopping();

		if (!forbidProcessingPendings) {
			for (Iterator<Uri> i = mPendingImageUris.iterator(); i.hasNext(); i.remove()) {
				Uri foo = i.next();
				Log.d(Config.LOGTAG,"ConversationsActivity.onBackendConnected() - attaching image to conversations. stopping="+Boolean.toString(stopping));
				attachImageToConversation(getSelectedConversation(), foo);
			}

			for (Iterator<Uri> i = mPendingFileUris.iterator(); i.hasNext(); i.remove()) {
				Log.d(Config.LOGTAG,"ConversationsActivity.onBackendConnected() - attaching file to conversations. stopping="+Boolean.toString(stopping));
				attachFileToConversation(getSelectedConversation(), i.next());
			}

			if (mPendingGeoUri != null) {
				attachLocationToConversation(getSelectedConversation(), mPendingGeoUri);
				mPendingGeoUri = null;
			}
		}
		forbidProcessingPendings = false;

		if (!ExceptionHelper.checkForCrash(this, this.xmppConnectionService) && !mRedirected.get()) {
			openBatteryOptimizationDialogIfNeeded();
		}
		if (isConversationsOverviewVisable() && isConversationsOverviewHideable()) {
			xmppConnectionService.getNotificationService().setOpenConversation(null);
		} else {
			xmppConnectionService.getNotificationService().setOpenConversation(getSelectedConversation());
		}
	}

	private boolean isStopping() {
		if (Build.VERSION.SDK_INT >= 17) {
			return isFinishing() || isDestroyed();
		} else {
			return isFinishing();
		}
	}

	private void reInitLatestConversation() {
		showConversationsOverview();
		clearPending();
		setSelectedConversation(conversationList.get(0));
		this.mConversationFragment.reInit(getSelectedConversation());
	}

	private void handleViewConversationIntent(final Intent intent) {
		final String uuid = intent.getStringExtra(CONVERSATION);
		final String downloadUuid = intent.getStringExtra(EXTRA_DOWNLOAD_UUID);
		final String text = intent.getStringExtra(TEXT);
		final String nick = intent.getStringExtra(NICK);
		final boolean pm = intent.getBooleanExtra(PRIVATE_MESSAGE, false);
		if (selectConversationByUuid(uuid)) {
			this.mConversationFragment.reInit(getSelectedConversation());
			if (nick != null) {
				if (pm) {
					Jid jid = getSelectedConversation().getJid();
					try {
						Jid next = Jid.fromParts(jid.getLocalpart(), jid.getDomainpart(), nick);
						this.mConversationFragment.privateMessageWith(next);
					} catch (final InvalidJidException ignored) {
						//do nothing
					}
				} else {
					this.mConversationFragment.highlightInConference(nick);
				}
			} else {
				this.mConversationFragment.appendText(text);
			}
			hideConversationsOverview();
			mUnprocessedNewIntent = false;
			openConversation();
			if (mContentView instanceof SlidingPaneLayout) {
				updateActionBarTitle(true); //fixes bug where slp isn't properly closed yet
			}
			if (downloadUuid != null) {
				final Message message = mSelectedConversation.findMessageWithFileAndUuid(downloadUuid);
				if (message != null) {
					startDownloadable(message);
				}
			}
		} else {
			mUnprocessedNewIntent = false;
		}
	}

	private boolean selectConversationByUuid(String uuid) {
		if (uuid == null) {
			return false;
		}
		for (Conversation aConversationList : conversationList) {
			if (aConversationList.getUuid().equals(uuid)) {
				setSelectedConversation(aConversationList);
				return true;
			}
		}
		return false;
	}

	@Override
	protected void unregisterListeners() {
		super.unregisterListeners();
		xmppConnectionService.getNotificationService().setOpenConversation(null);
	}

	@SuppressLint("NewApi")
	private static List<Uri> extractUriFromIntent(final Intent intent) {
		List<Uri> uris = new ArrayList<>();
		if (intent == null) {
			return uris;
		}
		Uri uri = intent.getData();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && uri == null) {
			final ClipData clipData = intent.getClipData();
			if (clipData != null) {
				for (int i = 0; i < clipData.getItemCount(); ++i) {
					uris.add(clipData.getItemAt(i).getUri());
				}
			}
		} else {
			uris.add(uri);
		}
		return uris;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode == RESULT_OK) {
			if (requestCode == REQUEST_DECRYPT_PGP) {
				mConversationFragment.onActivityResult(requestCode, resultCode, data);
			} else if (requestCode == REQUEST_CHOOSE_PGP_ID) {
				// the user chose OpenPGP for encryption and selected his key in the PGP provider
				if (xmppConnectionServiceBound) {
					if (data.getExtras().containsKey(OpenPgpApi.EXTRA_SIGN_KEY_ID)) {
						// associate selected PGP keyId with the account
						mSelectedConversation.getAccount().setPgpSignId(data.getExtras().getLong(OpenPgpApi.EXTRA_SIGN_KEY_ID));
						// we need to announce the key as described in XEP-027
						announcePgp(mSelectedConversation.getAccount(), null, onOpenPGPKeyPublished);
					} else {
						choosePgpSignId(mSelectedConversation.getAccount());
					}
					this.mPostponedActivityResult = null;
				} else {
					this.mPostponedActivityResult = new Pair<>(requestCode, data);
				}
			} else if (requestCode == REQUEST_ANNOUNCE_PGP) {
				if (xmppConnectionServiceBound) {
					announcePgp(mSelectedConversation.getAccount(), mSelectedConversation, onOpenPGPKeyPublished);
					this.mPostponedActivityResult = null;
				} else {
					this.mPostponedActivityResult = new Pair<>(requestCode, data);
				}
			} else if (requestCode == ATTACHMENT_CHOICE_CHOOSE_IMAGE) {
				mPendingImageUris.clear();
				mPendingImageUris.addAll(extractUriFromIntent(data));
				if (xmppConnectionServiceBound) {
					for (Iterator<Uri> i = mPendingImageUris.iterator(); i.hasNext(); i.remove()) {
						Log.d(Config.LOGTAG,"ConversationsActivity.onActivityResult() - attaching image to conversations. CHOOSE_IMAGE");
						attachImageToConversation(getSelectedConversation(), i.next());
					}
				}
			} else if (requestCode == ATTACHMENT_CHOICE_CHOOSE_FILE || requestCode == ATTACHMENT_CHOICE_RECORD_VOICE) {
				final List<Uri> uris = extractUriFromIntent(data);
				final Conversation c = getSelectedConversation();
				final OnPresenceSelected callback = new OnPresenceSelected() {
					@Override
					public void onPresenceSelected() {
						mPendingFileUris.clear();
						mPendingFileUris.addAll(uris);
						if (xmppConnectionServiceBound) {
							for (Iterator<Uri> i = mPendingFileUris.iterator(); i.hasNext(); i.remove()) {
								Log.d(Config.LOGTAG,"ConversationsActivity.onActivityResult() - attaching file to conversations. CHOOSE_FILE/RECORD_VOICE");
								attachFileToConversation(c, i.next());
							}
						}
					}
				};
				if (c == null || c.getMode() == Conversation.MODE_MULTI
						|| FileBackend.allFilesUnderSize(this, uris, getMaxHttpUploadSize(c))
						|| c.getNextEncryption() == Message.ENCRYPTION_OTR) {
					callback.onPresenceSelected();
				} else {
					selectPresence(c, callback);
				}
			} else if (requestCode == ATTACHMENT_CHOICE_TAKE_PHOTO) {
				if (mPendingImageUris.size() == 1) {
					Uri uri = FileBackend.getIndexableTakePhotoUri(mPendingImageUris.get(0));
					mPendingImageUris.set(0, uri);
					if (xmppConnectionServiceBound) {
						Log.d(Config.LOGTAG,"ConversationsActivity.onActivityResult() - attaching image to conversations. TAKE_PHOTO");
						attachImageToConversation(getSelectedConversation(), uri);
						mPendingImageUris.clear();
					}
					if (!Config.ONLY_INTERNAL_STORAGE) {
						Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
						intent.setData(uri);
						sendBroadcast(intent);
					}
				} else {
					mPendingImageUris.clear();
				}
			} else if (requestCode == ATTACHMENT_CHOICE_LOCATION) {
				double latitude = data.getDoubleExtra("latitude", 0);
				double longitude = data.getDoubleExtra("longitude", 0);
				this.mPendingGeoUri = Uri.parse("geo:" + String.valueOf(latitude) + "," + String.valueOf(longitude));
				if (xmppConnectionServiceBound) {
					attachLocationToConversation(getSelectedConversation(), mPendingGeoUri);
					this.mPendingGeoUri = null;
				}
			} else if (requestCode == REQUEST_TRUST_KEYS_TEXT || requestCode == REQUEST_TRUST_KEYS_MENU) {
				this.forbidProcessingPendings = !xmppConnectionServiceBound;
				if (xmppConnectionServiceBound) {
					mConversationFragment.onActivityResult(requestCode, resultCode, data);
					this.mPostponedActivityResult = null;
				} else {
					this.mPostponedActivityResult = new Pair<>(requestCode, data);
				}

			} else if (requestCode == REQUEST_CREATE_CONFERENCE) {
				if (xmppConnectionServiceBound) {
					final Account account = extractAccount(data);
					final String subject = data.getStringExtra("subject");
					final List<Jid> jids = new ArrayList<>();
					if (data.getBooleanExtra("multiple", false)) {
						final String[] toAdd = data.getStringArrayExtra("contacts");
						for (final String item : toAdd) {
							try {
								jids.add(Jid.fromString(item));
							} catch (final InvalidJidException e) {
								//ignored
							}
						}
					} else {
						try {
							jids.add(Jid.fromString(data.getStringExtra("contact")));
						} catch (final Exception e) {
							//ignored
						}
					}
					if (account != null && jids.size() > 0) {
						if (xmppConnectionService.createAdhocConference(account, subject, jids, mAdhocConferenceCallback)) {
							mCreateConferenceToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
							mCreateConferenceToast.show();
						}
					}
					this.mPostponedActivityResult = null;
				} else {
					this.mPostponedActivityResult = new Pair<>(requestCode, data);
				}
			}
		} else {
			mPendingImageUris.clear();
			mPendingFileUris.clear();
			if (requestCode == ConversationActivity.REQUEST_DECRYPT_PGP) {
				mConversationFragment.onActivityResult(requestCode, resultCode, data);
			}
			if (requestCode == REQUEST_BATTERY_OP) {
				setNeverAskForBatteryOptimizationsAgain();
			}
		}
	}

	private long getMaxHttpUploadSize(Conversation conversation) {
		final XmppConnection connection = conversation.getAccount().getXmppConnection();
		return connection == null ? -1 : connection.getFeatures().getMaxHttpUploadSize();
	}

	private void setNeverAskForBatteryOptimizationsAgain() {
		getPreferences().edit().putBoolean("show_battery_optimization", false).apply();
	}

	private void openBatteryOptimizationDialogIfNeeded() {
		if (hasAccountWithoutPush()
				&& isOptimizingBattery()
				&& getPreferences().getBoolean("show_battery_optimization", true)) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(R.string.battery_optimizations_enabled);
			builder.setMessage(R.string.battery_optimizations_enabled_dialog);
			builder.setPositiveButton(R.string.next, new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
					Uri uri = Uri.parse("package:" + getPackageName());
					intent.setData(uri);
					try {
						startActivityForResult(intent, REQUEST_BATTERY_OP);
					} catch (ActivityNotFoundException e) {
						Toast.makeText(ConversationActivity.this, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show();
					}
				}
			});
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
				builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
					@Override
					public void onDismiss(DialogInterface dialog) {
						setNeverAskForBatteryOptimizationsAgain();
					}
				});
			}
			AlertDialog dialog = builder.create();
			dialog.setCanceledOnTouchOutside(false);
			dialog.show();
		}
	}

	private boolean hasAccountWithoutPush() {
		for(Account account : xmppConnectionService.getAccounts()) {
			if (account.getStatus() != Account.State.DISABLED
					&& !xmppConnectionService.getPushManagementService().availableAndUseful(account)) {
				return true;
			}
		}
		return false;
	}

	private void attachLocationToConversation(Conversation conversation, Uri uri) {
		if (conversation == null) {
			return;
		}
		xmppConnectionService.attachLocationToConversation(conversation,uri, new UiCallback<Message>() {

			@Override
			public void success(Message message) {
				xmppConnectionService.sendMessage(message);
			}

			@Override
			public void error(int errorCode, Message object) {

			}

			@Override
			public void userInputRequried(PendingIntent pi, Message object) {

			}
		});
	}

	public void startVoiceMessageRecording(final Conversation conversation) {
		if (!hasRecordAudioPermission(REQUEST_RECORD_VOICE_MESSAGE)) {
			return;
		}
		final java.io.File dir = new java.io.File(getFilesDir(), "Files");
		dir.mkdirs();
		mVoiceRecordingFile = new java.io.File(dir, "RIKITIKI_VOICE_" + System.currentTimeMillis() + ".m4a");
		mVoiceRecordingCancelled = false;
		mVoiceRecorder = new pl.rikitiki.im.utils.VoiceRecorder();
		try {
			mVoiceRecorder.start(mVoiceRecordingFile, new pl.rikitiki.im.utils.VoiceRecorder.Callback() {
				@Override
				public void onStopped() {
					onVoiceRecordingStopped(conversation, null);
				}

				@Override
				public void onError(final Exception e) {
					onVoiceRecordingStopped(conversation, e);
				}
			});
		} catch (final Exception e) {
			Log.d(Config.LOGTAG, "failed to start voice message recording", e);
			Toast.makeText(this, R.string.voice_message_recording_failed, Toast.LENGTH_SHORT).show();
			mVoiceRecorder = null;
			return;
		}
		mVoiceRecordingStartedAt = SystemClock.elapsedRealtime();
		final View view = getLayoutInflater().inflate(R.layout.dialog_record_voice, null);
		final TextView timer = (TextView) view.findViewById(R.id.record_voice_timer);
		mVoiceRecordingDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.record_voice_message)
				.setView(view)
				.setCancelable(false)
				.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(final DialogInterface dialog, final int which) {
						cancelVoiceMessageRecording();
					}
				})
				.setPositiveButton(R.string.stop_and_send, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(final DialogInterface dialog, final int which) {
						finishVoiceMessageRecording(conversation);
					}
				})
				.show();
		final Runnable ticker = new Runnable() {
			@Override
			public void run() {
				if (mVoiceRecorder == null) {
					return;
				}
				final long seconds = (SystemClock.elapsedRealtime() - mVoiceRecordingStartedAt) / 1000;
				timer.setText(String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
				mVoiceRecordingHandler.postDelayed(this, 1000);
			}
		};
		mVoiceRecordingHandler.post(ticker);
	}

	private void finishVoiceMessageRecording(final Conversation conversation) {
		if (mVoiceRecorder == null) {
			return;
		}
		mVoiceRecorder.stop();
	}

	private void cancelVoiceMessageRecording() {
		if (mVoiceRecorder == null) {
			return;
		}
		mVoiceRecordingCancelled = true;
		mVoiceRecorder.cancel();
	}

	private void onVoiceRecordingStopped(final Conversation conversation, final Exception error) {
		final java.io.File file = mVoiceRecordingFile;
		final boolean cancelled = mVoiceRecordingCancelled;
		cleanupVoiceRecordingState();
		if (cancelled) {
			if (file != null) {
				file.delete();
			}
			return;
		}
		if (error != null) {
			Log.d(Config.LOGTAG, "voice message recording failed to finalize", error);
			Toast.makeText(this, R.string.voice_message_recording_failed, Toast.LENGTH_SHORT).show();
			if (file != null) {
				file.delete();
			}
			return;
		}
		final Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", file);
		attachFileToConversation(conversation, uri);
	}

	private void cleanupVoiceRecordingState() {
		mVoiceRecorder = null;
		mVoiceRecordingFile = null;
		mVoiceRecordingHandler.removeCallbacksAndMessages(null);
		if (mVoiceRecordingDialog != null) {
			mVoiceRecordingDialog.dismiss();
			mVoiceRecordingDialog = null;
		}
	}

	private void attachFileToConversation(Conversation conversation, Uri uri) {
		if (conversation == null) {
			return;
		}
		final Toast prepareFileToast = Toast.makeText(getApplicationContext(),getText(R.string.preparing_file), Toast.LENGTH_LONG);
		prepareFileToast.show();
		xmppConnectionService.attachFileToConversation(conversation, uri, new UiInformableCallback<Message>() {
			@Override
			public void inform(final String text) {
				hidePrepareFileToast(prepareFileToast);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						replaceToast(text);
					}
				});
			}

			@Override
			public void success(Message message) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						hideToast();
					}
				});
				hidePrepareFileToast(prepareFileToast);
				xmppConnectionService.sendMessage(message);
			}

			@Override
			public void error(final int errorCode, Message message) {
				hidePrepareFileToast(prepareFileToast);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						replaceToast(getString(errorCode));
					}
				});

			}

			@Override
			public void userInputRequried(PendingIntent pi, Message message) {
				hidePrepareFileToast(prepareFileToast);
			}
		});
	}

	public void attachImageToConversation(Uri uri) {
		this.attachImageToConversation(getSelectedConversation(), uri);
	}

	private void attachImageToConversation(Conversation conversation, Uri uri) {
		if (conversation == null) {
			return;
		}
		final Toast prepareFileToast = Toast.makeText(getApplicationContext(),getText(R.string.preparing_image), Toast.LENGTH_LONG);
		prepareFileToast.show();
		xmppConnectionService.attachImageToConversation(conversation, uri,
				new UiCallback<Message>() {

					@Override
					public void userInputRequried(PendingIntent pi, Message object) {
						hidePrepareFileToast(prepareFileToast);
					}

					@Override
					public void success(Message message) {
						hidePrepareFileToast(prepareFileToast);
						xmppConnectionService.sendMessage(message);
					}

					@Override
					public void error(final int error, Message message) {
						hidePrepareFileToast(prepareFileToast);
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								replaceToast(getString(error));
							}
						});
					}
				});
	}

	private void hidePrepareFileToast(final Toast prepareFileToast) {
		if (prepareFileToast != null) {
			runOnUiThread(new Runnable() {

				@Override
				public void run() {
					prepareFileToast.cancel();
				}
			});
		}
	}

	public void updateConversationList() {
		xmppConnectionService.populateWithOrderedConversations(conversationList);
		// Uses the full, unfiltered list — the contacts-without-a-conversation
		// footer is about 1:1 contacts specifically, and a roster Contact's JID
		// can only ever match a MODE_SINGLE conversation, so this is correct
		// regardless of which tab is about to filter conversationList below.
		updateContactsWithoutConversation();
		final Iterator<Conversation> tabFilter = conversationList.iterator();
		while (tabFilter.hasNext()) {
			if (tabFilter.next().getMode() != mConversationsTabMode) {
				tabFilter.remove();
			}
		}
		if (!conversationList.contains(mSelectedConversation)) {
			mSelectedConversation = null;
		}
		if (swipedConversation != null) {
			if (swipedConversation.isRead()) {
				conversationList.remove(swipedConversation);
			} else {
				listView.discardUndo();
			}
		}
		listAdapter.notifyDataSetChanged();
	}

	private void updateContactsWithoutConversation() {
		contactsWithoutConversation.clear();
		for (final Account account : xmppConnectionService.getAccounts()) {
			if (account.getStatus() != Account.State.DISABLED) {
				for (final Contact contact : account.getRoster().getContacts()) {
					if (contact.showInRoster() && xmppConnectionService.find(conversationList, contact) == null) {
						contactsWithoutConversation.add(contact);
					}
				}
			}
		}
		Collections.sort(contactsWithoutConversation);
		contactsWithoutConversationContainer.removeAllViews();
		final LayoutInflater inflater = LayoutInflater.from(this);
		for (final Contact contact : contactsWithoutConversation) {
			final View view = inflater.inflate(R.layout.contact, contactsWithoutConversationContainer, false);
			((TextView) view.findViewById(R.id.contact_display_name)).setText(contact.getDisplayName());
			final TextView jid = (TextView) view.findViewById(R.id.contact_jid);
			jid.setText(contact.getDisplayJid());
			view.findViewById(R.id.tags).setVisibility(View.GONE);
			((ImageView) view.findViewById(R.id.contact_photo)).setImageBitmap(
					avatarService().get(contact, getPixel(48), false));
			view.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					openConversationForContact(contact);
				}
			});
			contactsWithoutConversationContainer.addView(view);
		}
	}

	private void openConversationForContact(final Contact contact) {
		final Conversation conversation = xmppConnectionService.findOrCreateConversation(contact.getAccount(), contact.getJid(), false, true);
		switchToConversation(conversation);
	}

	public void runIntent(PendingIntent pi, int requestCode) {
		try {
			this.startIntentSenderForResult(pi.getIntentSender(), requestCode,
					null, 0, 0, 0);
		} catch (final SendIntentException ignored) {
		}
	}

	public void encryptTextMessage(Message message) {
		xmppConnectionService.getPgpEngine().encrypt(message,
				new UiCallback<Message>() {

					@Override
					public void userInputRequried(PendingIntent pi,Message message) {
						ConversationActivity.this.runIntent(pi,ConversationActivity.REQUEST_SEND_MESSAGE);
					}

					@Override
					public void success(Message message) {
						message.setEncryption(Message.ENCRYPTION_DECRYPTED);
						xmppConnectionService.sendMessage(message);
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								mConversationFragment.messageSent();
							}
						});
					}

					@Override
					public void error(final int error, Message message) {
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								mConversationFragment.doneSendingPgpMessage();
								Toast.makeText(ConversationActivity.this,
										R.string.unable_to_connect_to_keychain,
										Toast.LENGTH_SHORT
								).show();
							}
						});

					}
				});
	}

	public boolean useSendButtonToIndicateStatus() {
		return getPreferences().getBoolean("send_button_status", getResources().getBoolean(R.bool.send_button_status));
	}

	public boolean indicateReceived() {
		return getPreferences().getBoolean("indicate_received", getResources().getBoolean(R.bool.indicate_received));
	}

	public boolean useGreenBackground() {
		return getPreferences().getBoolean("use_green_background",getResources().getBoolean(R.bool.use_green_background));
	}

	protected boolean trustKeysIfNeeded(int requestCode) {
		return trustKeysIfNeeded(requestCode, ATTACHMENT_CHOICE_INVALID);
	}

	protected boolean trustKeysIfNeeded(int requestCode, int attachmentChoice) {
		AxolotlService axolotlService = mSelectedConversation.getAccount().getAxolotlService();
		final List<Jid> targets = axolotlService.getCryptoTargets(mSelectedConversation);
		boolean hasUnaccepted = !mSelectedConversation.getAcceptedCryptoTargets().containsAll(targets);
		boolean hasUndecidedOwn = !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided()).isEmpty();
		boolean hasUndecidedContacts = !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets).isEmpty();
		boolean hasPendingKeys = !axolotlService.findDevicesWithoutSession(mSelectedConversation).isEmpty();
		boolean hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets);
		if(hasUndecidedOwn || hasUndecidedContacts || hasPendingKeys || hasNoTrustedKeys || hasUnaccepted) {
			axolotlService.createSessionsIfNeeded(mSelectedConversation);
			Intent intent = new Intent(getApplicationContext(), TrustKeysActivity.class);
			String[] contacts = new String[targets.size()];
			for(int i = 0; i < contacts.length; ++i) {
				contacts[i] = targets.get(i).toString();
			}
			intent.putExtra("contacts", contacts);
			intent.putExtra(EXTRA_ACCOUNT, mSelectedConversation.getAccount().getJid().toBareJid().toString());
			intent.putExtra("choice", attachmentChoice);
			intent.putExtra("conversation",mSelectedConversation.getUuid());
			startActivityForResult(intent, requestCode);
			return true;
		} else {
			return false;
		}
	}

	@Override
	protected void refreshUiReal() {
		updateConversationList();
		if (conversationList.size() > 0) {
			if (!this.mConversationFragment.isAdded()) {
				Log.d(Config.LOGTAG,"fragment NOT added to activity. detached="+Boolean.toString(mConversationFragment.isDetached()));
			}
			if (getSelectedConversation() == null) {
				reInitLatestConversation();
			} else {
				ConversationActivity.this.mConversationFragment.updateMessages();
				updateActionBarTitle();
				invalidateOptionsMenu();
			}
		} else if (!contactsWithoutConversation.isEmpty()) {
			showConversationsOverview();
		} else if (!xmppConnectionService.getConversations().isEmpty()) {
			// This tab (Chats/Rooms) is empty, but conversations exist on the
			// other one — just show this tab's empty overview instead of
			// redirecting away to "start a conversation".
			showConversationsOverview();
		} else {
			if (!isStopping() && mRedirected.compareAndSet(false, true)) {
				redirectToStartConversationActivity();
			}
			Log.d(Config.LOGTAG,"not updating conversations fragment because conversations list size was 0");
		}
	}

	@Override
	public void onAccountUpdate() {
		this.refreshUi();
	}

	@Override
	public void onConversationUpdate() {
		this.refreshUi();
	}

	@Override
	public void onRosterUpdate() {
		this.refreshUi();
	}

	@Override
	public void OnUpdateBlocklist(Status status) {
		this.refreshUi();
	}

	public void unblockConversation(final Blockable conversation) {
		xmppConnectionService.sendUnblockRequest(conversation);
	}

	public boolean enterIsSend() {
		return getPreferences().getBoolean("enter_is_send",getResources().getBoolean(R.bool.enter_is_send));
	}

	@Override
	public void onShowErrorToast(final int resId) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(ConversationActivity.this,resId,Toast.LENGTH_SHORT).show();
			}
		});
	}

	public boolean highlightSelectedConversations() {
		return !isConversationsOverviewHideable() || this.conversationWasSelectedByKeyboard;
	}




}
