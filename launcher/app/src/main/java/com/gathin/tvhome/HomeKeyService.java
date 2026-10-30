package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * With home_redirect_component set, every one of those redirects opens that app instead of this launcher itself
 * (checked for installed, enabled and itself answering a MAIN+HOME query on every redirect, falling back to
 * this launcher otherwise). This launcher stays installed and keeps the accessibility binding either way; only
 * what Home shows changes. A target that does not stay in the foreground - crashing, or finishing straight
 * back to whatever is behind it - would otherwise retrigger this service as fast as Android cycles the
 * windows; CUSTOM_HOME_GUARD_COUNT attempts within CUSTOM_HOME_GUARD_WINDOW_MS fall back to this launcher
 * instead, for that call only. Android does not consume the Home key on its way here (#1274 needs it to also
 * reach the system), so one press alone opens the custom target through two paths - the window-watch above,
 * almost at once, and redirectHomeKey()'s own delayed call, HOME_KEY_DELAY_MS later - and only the one with no
 * Home key in the last HOME_KEY_RECENT_MS counts toward the guard, so ordinary presses, however fast, cannot
 * trip it themselves (countsTowardCustomHomeGuard).
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {
    private static final String TAG = "TvHome.HomeKeyService";

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** stock_home_redirect, the stock launcher's package. */
    private String stockHomePackage = "";
    /** home_redirect_component: another app's Home (or "package/.Activity") opened in this launcher's place;
     *  empty keeps the historical behaviour of opening this launcher itself. */
    private String homeRedirectComponent = "";
    /** How many recent attempts to launch home_redirect_component's target the loop guard remembers, and the
     *  window they must all land inside before it skips one (openHome, loopingOnCustomHome). A target that
     *  does not stay in the foreground sends the window-watch straight back into another redirect, with no
     *  debounce on that path, as fast as Android can cycle the windows - CUSTOM_HOME_GUARD_COUNT in a row
     *  caught that in well under a second on an Onn 4K Pro (#SetupFileOpenActivity). */
    static final int CUSTOM_HOME_GUARD_COUNT = 3;
    static final long CUSTOM_HOME_GUARD_WINDOW_MS = 2_000L;
    /** elapsedRealtime of the last CUSTOM_HOME_GUARD_COUNT attempts to launch home_redirect_component's
     *  target, oldest at customHomeAttemptNext; see loopingOnCustomHome. No separate "gave up" state: once the
     *  oldest entry ages past CUSTOM_HOME_GUARD_WINDOW_MS the next attempt is let through again. */
    private final long[] customHomeAttempts = freshGuardRing();
    /** How long after a Home key press a window-watch attempt is still "the same press" and does not count
     *  toward the guard: Android does not consume the Home key on the way to redirectHomeKey() (#1274 needs
     *  it to reach the system too), so a single physical press makes the system open launcherx, which the
     *  window-watch sees and redirects for - and redirectHomeKey()'s own delayed call to openHome() can follow
     *  moments later, from the same one press. Without this, two presses alone can look like the start of a
     *  loop (#SetupFileOpenActivity, found on an Onn 4K Pro: two real presses cost 4 counted attempts - the
     *  window-watch's and the key path's, for each - tripping on the second press). */
    static final long HOME_KEY_RECENT_MS = 1_000L;
    /** elapsedRealtime of the last Home (or ALL_APPS) key event seen in onKeyEvent, set there rather than in
     *  openHome() so the window-watch call that the same press's launcherx flash triggers - which fires first,
     *  well before redirectHomeKey()'s own HOME_KEY_DELAY_MS - already sees it. Seeded far enough in the past
     *  that "no key has ever been pressed" never reads as recent, for the same reason freshGuardRing() seeds
     *  the ring rather than leaving it at zero. */
    private long lastHomeKeyAt = Long.MIN_VALUE / 2;

    /** A ring buffer whose slots read as arbitrarily long ago until each is actually written, so the guard
     *  cannot misfire on the first few attempts after a service rebind: plain zero-initialization would have
     *  `now - 0` read as recent for any small now, which is exactly what elapsedRealtime() is in the first
     *  couple of seconds after a real device boot (and what a JVM test's own now values look like too). */
    static long[] freshGuardRing() {
        long[] ring = new long[CUSTOM_HOME_GUARD_COUNT];
        for (int i = 0; i < ring.length; i++) ring[i] = Long.MIN_VALUE / 2;
        return ring;
    }
    private int customHomeAttemptNext;
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries, and the package of the activity in front, from window changes while there are any. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    private String frontPackage = "";
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;
    /** The bound helper, so the opt-in broadcast can arm it without waiting for a new connection. */
    private static HomeKeyService running;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        RemoteKeys.release(this);
        stopWaitingForUnlock();
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        homeRedirectComponent = config.homeRedirectComponent;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        // power_on_home, off by default. Not with boot_to_live_tv, or with Live TV picked as the Open on start app:
        // those open Live TV on purpose at boot.
        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty() || watchFront)) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        if (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty()) probeAtBoot();
        armStandbyExit();
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        String target = keys.get(code);
        String alt = front.get(code);
        return alt != null && RemoteKeys.inFront(target, frontPackage) ? alt : target;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, inFront(appKeys, frontAppKeys, event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            lastHomeKeyAt = SystemClock.elapsedRealtime();
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome(true);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            lastHomeKeyAt = SystemClock.elapsedRealtime();
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        lastHomeKeyAt = SystemClock.elapsedRealtime();
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome(true);
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty())
                && event.getPackageName() != null && event.getClassName() != null) {
            try {
                ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
                getPackageManager().getActivityInfo(window, 0);
                frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
            }
        }
        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (powerOnRedirect(powerOnLiveTv, event.getPackageName(), SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome(false);
            return;
        }
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        CharSequence cls = event.getClassName();
        if (!stockHome.isEmpty() && cls != null && stockHome.contains(cls.toString())) openHome(false);
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome(false);
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here. */
    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(() -> openHome(true), HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    /** fromKeyPress: true from a key event's own handling (redirectHomeKey's delayed callback,
     *  home_long_press_home, the ALL_APPS branch); false from onAccessibilityEvent's window-watch and
     *  power_on_home(_always), which no key drives. Decides whether this call's custom-target attempt counts
     *  toward the loop guard - see countsTowardCustomHomeGuard. */
    private void openHome(boolean fromKeyPress) {
        Intent custom = redirectTarget();
        if (custom != null) {
            long now = SystemClock.elapsedRealtime();
            boolean counts = countsTowardCustomHomeGuard(fromKeyPress, lastHomeKeyAt, now);
            boolean blocked = counts && loopingOnCustomHome(customHomeAttempts, customHomeAttemptNext, now);
            Log.d(TAG, "openHome: " + (fromKeyPress ? "key press" : "window-watch/power-on")
                    + ", counts=" + counts + ", blocked=" + blocked);
            if (blocked) {
                Log.w(TAG, "home_redirect_component (" + homeRedirectComponent + ") launched "
                        + CUSTOM_HOME_GUARD_COUNT + " times in " + CUSTOM_HOME_GUARD_WINDOW_MS
                        + "ms - it is not staying in the foreground, so this Home goes to this launcher instead");
            } else {
                if (counts) customHomeAttemptNext = (customHomeAttemptNext + 1) % customHomeAttempts.length;
                Intent toStart = custom.addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (tryCustomHome(() -> startActivity(toStart))) return;
            }
        }
        Intent open = stockHomeStays() ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        startActivity(open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /** True when a custom-target launch attempt should count toward the loop guard: never for one triggered
     *  directly by a key press (it is the press, and redirectHomeKey's delayed call and the window-watch call
     *  the same press's launcherx flash triggers can both fire for it - counting the key-driven one would
     *  double that), and not for a window-watch (or power-on) attempt either while a key press landed within
     *  HOME_KEY_RECENT_MS of now (the same press's flash reaching onAccessibilityEvent). A target that
     *  retriggers the window-watch on its own has no key behind any of its attempts, so this is still true for
     *  every one of them, leaving loopingOnCustomHome exactly as effective there as before. Package-private
     *  and the state passed in rather than read from instance fields, so a host-side JVM test can drive it
     *  directly. */
    static boolean countsTowardCustomHomeGuard(boolean fromKeyPress, long lastHomeKeyAt, long now) {
        return !fromKeyPress && now - lastHomeKeyAt >= HOME_KEY_RECENT_MS;
    }

    /** True once CUSTOM_HOME_GUARD_COUNT attempts to launch home_redirect_component's target have already
     *  landed within CUSTOM_HOME_GUARD_WINDOW_MS of now, meaning this one should be skipped instead of adding
     *  another; otherwise records this attempt (by overwriting the oldest slot, attempts[next]) and returns
     *  false. attempts is a ring buffer of the last attempts.length attempts, oldest at next; the caller
     *  advances next (mod attempts.length) only when this returns false, since a skipped attempt was never
     *  recorded. elapsedRealtime, not wall-clock time, so a clock change cannot wedge or unstick the guard.
     *  Package-private and the ring buffer passed in rather than read from instance fields, so a host-side JVM
     *  test can drive it directly. */
    static boolean loopingOnCustomHome(long[] attempts, int next, long now) {
        int oldest = next % attempts.length;
        if (now - attempts[oldest] < CUSTOM_HOME_GUARD_WINDOW_MS) return true;
        attempts[oldest] = now;
        return false;
    }

    /** Runs home_redirect_component's launch; true if it did not throw. False only for
     *  ActivityNotFoundException or SecurityException - a target that passed redirectTarget()'s checks but
     *  could not actually be started (removed or disabled in between, or an unexported/permission-protected
     *  activity) - so openHome() falls through to this launcher's own Home instead of Home doing nothing.
     *  Any other RuntimeException is not ours to swallow and still propagates. Package-private and taking a
     *  Runnable, not the Intent or a Context, so a host-side JVM test can exercise the catch without a real
     *  Android Intent. */
    static boolean tryCustomHome(Runnable starter) {
        try {
            starter.run();
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    /** home_redirect_component: another app's Home in this launcher's place, resolved fresh on every call (not
     *  cached from loadConfig) so an app disabled or removed after this service was bound still falls back to
     *  this launcher instead of Home leading nowhere. null when the key is empty, the target is unavailable, or
     *  the target does not itself answer a MAIN+HOME query - installed, enabled and launchable is not enough
     *  (a utility activity such as a SEND/VIEW handler can be all three and still never be meant as a Home
     *  screen, which is how one that does not stay in the foreground reached loopingOnCustomHome at all). */
    private Intent redirectTarget() {
        if (homeRedirectComponent.isEmpty()) return null;
        ComponentName target;
        if (RemoteKeys.isActivity(homeRedirectComponent)) {
            target = ComponentName.unflattenFromString(homeRedirectComponent);
            if (target == null || !RemoteKeys.isEnabled(this, target)) return null;
        } else {
            Intent open = getPackageManager().getLeanbackLaunchIntentForPackage(homeRedirectComponent);
            if (open == null) open = getPackageManager().getLaunchIntentForPackage(homeRedirectComponent);
            target = open == null ? null : open.getComponent();
            if (target == null) return null;
        }
        if (!resolvesForHome(target)) {
            Log.w(TAG, "home_redirect_component (" + homeRedirectComponent + ") resolves to " + target
                    + ", which does not answer a MAIN+HOME query - it is not that app's Home activity");
            return null;
        }
        return new Intent().setComponent(target);
    }

    /** True when target's own package answers a MAIN+HOME query with exactly this component - the same test
     *  stock_home_redirect's own loadConfig() already runs for the stock launcher - so home_redirect_component
     *  is restricted to an activity actually meant to be Home. MATCH_DISABLED_COMPONENTS so this reads the
     *  manifest's own shape rather than overlapping with RemoteKeys.isEnabled's separate enabled check. */
    private boolean resolvesForHome(ComponentName target) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(target.getPackageName());
        List<String> homeActivities = new ArrayList<>();
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            homeActivities.add(ri.activityInfo.name);
        }
        return answersHome(target.getClassName(), homeActivities);
    }

    /** The class-name comparison at the heart of resolvesForHome, pulled out so a host-side JVM test can drive
     *  it with plain strings instead of a real PackageManager or ResolveInfo. */
    static boolean answersHome(String activityName, List<String> homeActivityNames) {
        return homeActivityNames.contains(activityName);
    }

    /** stock_home_redirect while Android's Home is still the stock launcher (launcherx on Google TV outranks this
     *  one). A Home intent would then put this launcher in the home task and make it the home process, and from
     *  Android 10 the stock launcher, no longer exempt from the limits on background activity starts, can't open
     *  apps for the remote's YouTube and Netflix buttons (#1274). openHome starts it like an app instead:
     *  CATEGORY_HOME without ACTION_MAIN is not a Home intent, but still sends MainActivity back to the first tile.
     *  A MainActivity already in the home task, as the install scripts' first start leaves it, is reused as it is
     *  until that task goes (a restart or a reinstall). With this launcher or Android's chooser as Home, openHome
     *  keeps the Home intent. */
    private boolean stockHomeStays() {
        if (stockHome.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
    }

    @Override
    public void onInterrupt() {
    }
}
