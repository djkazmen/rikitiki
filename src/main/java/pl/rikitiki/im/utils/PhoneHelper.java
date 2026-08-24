package pl.rikitiki.im.utils;

import android.Manifest;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.content.Loader.OnLoadCompleteListener;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Profile;
import android.telephony.TelephonyManager;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

public class PhoneHelper {

	// Reads raw phone numbers (as opposed to loadPhoneContacts()'s Jabber-IM-field
	// lookup) so they can be normalized and matched against phone-number-as-JID
	// Rikitiki accounts (see normalizeToJidLocalpart()).
	public static void loadPhoneContactsWithNumbers(Context context, final OnPhoneContactsLoadedListener listener) {
		final List<Bundle> phoneContacts = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
				&& context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
			listener.onPhoneContactsLoaded(phoneContacts);
			return;
		}
		final String[] PROJECTION = new String[]{
				ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
				ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
				ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
				ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
				ContactsContract.CommonDataKinds.Phone.NUMBER};

		CursorLoader mCursorLoader = new NotThrowCursorLoader(context,
				ContactsContract.CommonDataKinds.Phone.CONTENT_URI, PROJECTION, null, null, null);
		mCursorLoader.registerListener(0, new OnLoadCompleteListener<Cursor>() {

			@Override
			public void onLoadComplete(Loader<Cursor> arg0, Cursor cursor) {
				if (cursor != null) {
					while (cursor.moveToNext()) {
						Bundle contact = new Bundle();
						contact.putInt("phoneid", cursor.getInt(cursor
								.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)));
						contact.putString(
								"displayname",
								cursor.getString(cursor
										.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)));
						contact.putString("photouri", cursor.getString(cursor
								.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)));
						contact.putString("lookup", cursor.getString(cursor
								.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)));
						contact.putString(
								"phonenumber",
								cursor.getString(cursor
										.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)));
						phoneContacts.add(contact);
					}
					cursor.close();
				}

				if (listener != null) {
					listener.onPhoneContactsLoaded(phoneContacts);
				}
			}
		});
		try {
			mCursorLoader.startLoading();
		} catch (RejectedExecutionException e) {
			if (listener != null) {
				listener.onPhoneContactsLoaded(phoneContacts);
			}
		}
	}

	// Rikitiki is a Poland-only deployment (rikitiki.pl, every registered
	// account seen so far is +48) — used as a last-resort fallback region
	// below, not as the primary guess (a correctly detected device region
	// should always win first).
	private static final String FALLBACK_REGION = "PL";

	// Short operator/service codes saved as phone contacts (customer service
	// numbers, USSD-style codes, etc.) can — per a real user report — get
	// misparsed by libphonenumber as valid short-format numbers (Poland has
	// premium-rate/toll-free ranges shorter than a real 9-digit mobile
	// number) and then coincidentally collide with a registered account's
	// JID. A real Polish mobile subscriber number is always exactly 9
	// national digits, so anything with fewer than 9 digits total (even
	// with a country code) genuinely cannot be one — reject it before ever
	// asking libphonenumber to parse it.
	private static final int MIN_DIGITS = 9;

	// A raw contact number like "512 345 678" (no country code, as most phone
	// contacts are actually stored) can't just be stripped of non-digits — that
	// silently produces the wrong JID localpart unless the missing country code
	// is filled in. libphonenumber does this properly, defaulting to the SIM's
	// (falling back to network, then device locale) country when the number
	// itself has no explicit country code. That ambient detection is often
	// wrong or unavailable though (emulators, WiFi-only devices, a SIM from a
	// different country, roaming) — confirmed on a real device where SIM/network
	// both reported "US", silently failing to match every locally-formatted
	// number. If the detected region fails to produce a valid number, retry
	// once against FALLBACK_REGION before giving up.
	public static String normalizeToJidLocalpart(Context context, String rawNumber) {
		if (rawNumber == null || rawNumber.trim().isEmpty()) {
			return null;
		}
		if (countDigits(rawNumber) < MIN_DIGITS) {
			return null;
		}
		final String detectedRegion = getDeviceRegion(context);
		final String localpart = tryNormalize(rawNumber, detectedRegion);
		if (localpart != null) {
			return localpart;
		}
		if (FALLBACK_REGION.equals(detectedRegion)) {
			return null;
		}
		return tryNormalize(rawNumber, FALLBACK_REGION);
	}

	private static int countDigits(final String rawNumber) {
		int count = 0;
		for (int i = 0; i < rawNumber.length(); i++) {
			if (Character.isDigit(rawNumber.charAt(i))) {
				count++;
			}
		}
		return count;
	}

	private static String tryNormalize(final String rawNumber, final String region) {
		try {
			final PhoneNumberUtil util = PhoneNumberUtil.getInstance();
			final Phonenumber.PhoneNumber parsed = util.parse(rawNumber, region);
			if (!util.isValidNumber(parsed)) {
				return null;
			}
			final String e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
			return e164.startsWith("+") ? e164.substring(1) : e164;
		} catch (final NumberParseException e) {
			return null;
		}
	}

	private static String getDeviceRegion(Context context) {
		try {
			final TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
			if (tm != null) {
				final String simCountry = tm.getSimCountryIso();
				if (simCountry != null && simCountry.length() == 2) {
					return simCountry.toUpperCase(Locale.US);
				}
				final String networkCountry = tm.getNetworkCountryIso();
				if (networkCountry != null && networkCountry.length() == 2) {
					return networkCountry.toUpperCase(Locale.US);
				}
			}
		} catch (final Exception ignored) {
		}
		return Locale.getDefault().getCountry();
	}

	public static void loadPhoneContacts(Context context, final OnPhoneContactsLoadedListener listener) {
		final List<Bundle> phoneContacts = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
				&& context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
			listener.onPhoneContactsLoaded(phoneContacts);
			return;
		}
		final String[] PROJECTION = new String[]{ContactsContract.Data._ID,
				ContactsContract.Data.DISPLAY_NAME,
				ContactsContract.Data.PHOTO_URI,
				ContactsContract.Data.LOOKUP_KEY,
				ContactsContract.CommonDataKinds.Im.DATA};

		final String SELECTION = "(" + ContactsContract.Data.MIMETYPE + "=\""
				+ ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE
				+ "\") AND (" + ContactsContract.CommonDataKinds.Im.PROTOCOL
				+ "=\"" + ContactsContract.CommonDataKinds.Im.PROTOCOL_JABBER
				+ "\")";

		CursorLoader mCursorLoader = new NotThrowCursorLoader(context,
				ContactsContract.Data.CONTENT_URI, PROJECTION, SELECTION, null,
				null);
		mCursorLoader.registerListener(0, new OnLoadCompleteListener<Cursor>() {

			@Override
			public void onLoadComplete(Loader<Cursor> arg0, Cursor cursor) {
				if (cursor != null) {
					while (cursor.moveToNext()) {
						Bundle contact = new Bundle();
						contact.putInt("phoneid", cursor.getInt(cursor
								.getColumnIndex(ContactsContract.Data._ID)));
						contact.putString(
								"displayname",
								cursor.getString(cursor
										.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)));
						contact.putString("photouri", cursor.getString(cursor
								.getColumnIndex(ContactsContract.Data.PHOTO_URI)));
						contact.putString("lookup", cursor.getString(cursor
								.getColumnIndex(ContactsContract.Data.LOOKUP_KEY)));

						contact.putString(
								"jid",
								cursor.getString(cursor
										.getColumnIndex(ContactsContract.CommonDataKinds.Im.DATA)));
						phoneContacts.add(contact);
					}
					cursor.close();
				}

				if (listener != null) {
					listener.onPhoneContactsLoaded(phoneContacts);
				}
			}
		});
		try {
			mCursorLoader.startLoading();
		} catch (RejectedExecutionException e) {
			if (listener != null) {
				listener.onPhoneContactsLoaded(phoneContacts);
			}
		}
	}

	private static class NotThrowCursorLoader extends CursorLoader {

		public NotThrowCursorLoader(Context c, Uri u, String[] p, String s, String[] sa, String so) {
			super(c, u, p, s, sa, so);
		}

		@Override
		public Cursor loadInBackground() {

			try {
				return (super.loadInBackground());
			} catch (Throwable e) {
				return(null);
			}
		}

	}

	public static Uri getSelfiUri(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
				&& context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
			return null;
		}
		String[] mProjection = new String[]{Profile._ID, Profile.PHOTO_URI};
		Cursor mProfileCursor = context.getContentResolver().query(
				Profile.CONTENT_URI, mProjection, null, null, null);

		if (mProfileCursor == null || mProfileCursor.getCount() == 0) {
			return null;
		} else {
			mProfileCursor.moveToFirst();
			String uri = mProfileCursor.getString(1);
			mProfileCursor.close();
			if (uri == null) {
				return null;
			} else {
				return Uri.parse(uri);
			}
		}
	}

	public static String getVersionName(Context context) {
		final String packageName = context == null ? null : context.getPackageName();
		if (packageName != null) {
			try {
				return context.getPackageManager().getPackageInfo(packageName, 0).versionName;
			} catch (final PackageManager.NameNotFoundException | RuntimeException e) {
				return "unknown";
			}
		} else {
			return "unknown";
		}
	}
}
