package pl.rikitiki.im.ui;

import android.app.Activity;
import android.os.Bundle;
import android.preference.PreferenceManager;

import pl.rikitiki.im.R;

public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Boolean dark = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                        .getString("theme", "light").equals("dark");
        int mTheme = dark ? R.style.ConversationsTheme_Dark : R.style.ConversationsTheme;
        setTheme(mTheme);

        setContentView(R.layout.activity_about);
        // This screen is a plain Activity, not an XmppActivity subclass, so
        // it never got the app-wide edge-to-edge fix that lives in
        // XmppActivity.setContentView() (see ConversationFragment/
        // SettingsFragment for the same fix elsewhere) — without this, the
        // last few lines of the scrollable library-credits list render
        // behind the navigation bar on Android 15+ (targetSdk 35+ draws
        // behind system bars by default). Per that fix's own finding,
        // fitsSystemWindows has to go on the activity's actual inflated
        // root view, not the android.R.id.content wrapper around it —
        // the latter doesn't work.
        final android.view.View root = ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        root.setFitsSystemWindows(true);
    }
}
