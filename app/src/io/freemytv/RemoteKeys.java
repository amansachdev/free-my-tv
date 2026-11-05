package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"),
            FORMOVIE = is("Formovie"), SPPL = is("SPPL") || is("Realtek");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY || SPPL;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        int rawCode = e.getKeyCode();
        // When LauncherX is kept enabled (so Google TV's Dashboard / Notification side panel works on Settings button),
        // intercept KEYCODE_HOME in the accessibility key filter so the Home button always opens Free My TV.
        if (rawCode == KeyEvent.KEYCODE_HOME) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                openHome(c);
            }
            return true;
        }
        // Global TV remote keys that should open All Apps, Inputs, or Live TV in Free My TV:
        if (rawCode == KeyEvent.KEYCODE_ALL_APPS || rawCode == 297 /* KEYCODE_FEATURED_APP_1 */
                || rawCode == 313 /* KEYCODE_MACRO_1 */ || rawCode == KeyEvent.KEYCODE_EXPLORER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                openHomeAction(c, "open_all_apps");
            }
            return true;
        }
        if (rawCode == KeyEvent.KEYCODE_TV_INPUT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                openTvInputs(c);
            }
            return true;
        }
        if (rawCode == KeyEvent.KEYCODE_TV || rawCode == KeyEvent.KEYCODE_GUIDE || rawCode == KeyEvent.KEYCODE_DVR) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                Intent live = HomeApps.liveTv(c);
                if (live != null) {
                    try {
                        c.startActivity(live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                        openHomeAction(c, "open_inputs");
                    }
                } else {
                    openHomeAction(c, "open_inputs");
                }
            }
            return true;
        }
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad are left alone.
        // Note: Realtek/SPPL Bluetooth voice remotes (TV RCU) report SOURCE_JOYSTICK alongside SOURCE_KEYBOARD | SOURCE_DPAD,
        // so only ignore SOURCE_JOYSTICK when SOURCE_KEYBOARD is absent.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    && (source & InputDevice.SOURCE_KEYBOARD) == 0)) return false;
        int keyCode = rawCode == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : rawCode;
        if (SPPL) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_4: keyCode = Prefs.KEY_NETFLIX; break;
                case KeyEvent.KEYCODE_BUTTON_3: keyCode = Prefs.KEY_YOUTUBE; break;
                case KeyEvent.KEYCODE_BUTTON_8:
                case KeyEvent.KEYCODE_BUTTON_2: keyCode = Prefs.KEY_PRIME; break;
                case KeyEvent.KEYCODE_BUTTON_6:
                case KeyEvent.KEYCODE_BUTTON_7:
                case KeyEvent.KEYCODE_BUTTON_9:
                case KeyEvent.KEYCODE_BUTTON_10: keyCode = Prefs.KEY_TCL_CHANNEL; break;
            }
        }
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = Prefs.SONY_KEY + e.getScanCode();
        }
        if (SPPL && rawCode == KeyEvent.KEYCODE_BUTTON_16) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                openHomeAction(c, "open_all_apps");
            }
            return true;
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) {
            // On SPPL/Realtek remotes, the 4th button is the 6-dot All Apps button when not mapped to a custom app:
            if (SPPL && keyCode == Prefs.KEY_TCL_CHANNEL) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    openHomeAction(c, "open_all_apps");
                }
                return true;
            }
            return false;   // "Nothing": the key goes on as if we weren't here
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) {
                c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (SPPL && keyCode == Prefs.KEY_TCL_CHANNEL) {
                openHomeAction(c, "open_all_apps");
            }
        }
        return true;
    }

    static void openHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(c, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(home);
        } catch (Exception ignored) {
        }
    }

    static void openHomeAction(Context c, String extra) {
        Intent home = new Intent(c, HomeActivity.class)
                .putExtra(extra, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(home);
        } catch (Exception ignored) {
        }
    }

    private static void openTvInputs(Context c) {
        Intent switcher = new Intent("com.android.tv.action.VIEW_INPUTS")
                .setPackage("com.google.android.tv.inputplayer")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (switcher.resolveActivity(c.getPackageManager()) != null) {
            try {
                c.startActivity(switcher);
                return;
            } catch (Exception ignored) {
            }
        }
        openHomeAction(c, "open_inputs");
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
