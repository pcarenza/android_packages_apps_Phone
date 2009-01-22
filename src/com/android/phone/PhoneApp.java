/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SimCard;
import com.android.internal.telephony.TelephonyIntents;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.LocalPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.util.Config;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Top-level Application class for the Phone app.
 */
public class PhoneApp extends Application {
    /* package */ static final String LOG_TAG = "PhoneApp";
    /* package */ static final boolean DBG =
            (SystemProperties.getInt("ro.debuggable", 0) == 1);

    // Message codes; see mHandler below.
    private static final int EVENT_SIM_ABSENT = 1;
    private static final int EVENT_SIM_LOCKED = 2;
    private static final int EVENT_SIM_NETWORK_LOCKED = 3;
    private static final int EVENT_BLUETOOTH_HEADSET_CONNECTED = 4;
    private static final int EVENT_BLUETOOTH_HEADSET_DISCONNECTED = 5;
    private static final int EVENT_DATA_DISCONNECTED = 6;
    private static final int EVENT_WIRED_HEADSET_PLUG = 7;
    private static final int EVENT_SIM_STATE_CHANGED = 8;

    // The MMI codes are also used by the InCallScreen.
    public static final int MMI_INITIATE = 10;
    public static final int MMI_COMPLETE = 11;
    public static final int MMI_CANCEL = 12;
    // Don't use message codes larger than 99 here; those are reserved for
    // the individual Activities of the Phone UI.

    /**
     * Allowable values for the poke lock code (timeout between a user activity and the
     * going to sleep), please refer to {@link com.android.server.PowerManagerService}
     * for additional reference.
     *   SHORT uses the short delay for the timeout (SHORT_KEYLIGHT_DELAY, 6 sec)
     *   MEDIUM uses the medium delay for the timeout (MEDIUM_KEYLIGHT_DELAY, 15 sec)
     *   DEFAULT is the system-wide default delay for the timeout (1 min)
     */
    public enum ScreenTimeoutDuration {
        SHORT,
        MEDIUM,
        DEFAULT
    }

    /**
     * Allowable values for the wake lock code.
     *   SLEEP means the device can be put to sleep.
     *   PARTIAL means wake the processor, but we display can be kept off.
     *   FULL means wake both the processor and the display.
     */
    public enum WakeState {
        SLEEP,
        PARTIAL,
        FULL
    }

    private static PhoneApp sMe;

    // A few important fields we expose to the rest of the package
    // directly (rather than thru set/get methods) for efficiency.
    Phone phone;
    CallNotifier notifier;
    Ringer ringer;
    BluetoothHandsfree mBtHandsfree;
    PhoneInterfaceManager phoneMgr;

    // The InCallScreen instance (or null if the InCallScreen hasn't been
    // created yet.)
    private InCallScreen mInCallScreen;

    // The currently-active PUK entry activity and progress dialog.
    // Normally, these are the Emergency Dialer and the subsequent
    // progress dialog.  null if there is are no such objects in
    // the foreground.
    private Activity mPUKEntryActivity;
    private ProgressDialog mPUKEntryProgressDialog;

    private boolean mIsSimPinEnabled;
    private String mCachedSimPin;

    // True if a wired headset is currently plugged in, based on the state
    // from the latest Intent.ACTION_HEADSET_PLUG broadcast we received in
    // mReceiver.onReceive().
    private boolean mIsHeadsetPlugged;

    private WakeState mWakeState = WakeState.SLEEP;
    private ScreenTimeoutDuration mPokeLockSetting = ScreenTimeoutDuration.DEFAULT;
    private IBinder mPokeLockToken = new Binder();
    private IPowerManager mPowerManagerService;
    private PowerManager.WakeLock mWakeLock;
    private PowerManager.WakeLock mPartialWakeLock;
    private KeyguardManager mKeyguardManager;
    private KeyguardManager.KeyguardLock mKeyguardLock;

    // Broadcast receiver for various intent broadcasts (see onCreate())
    private final BroadcastReceiver mReceiver = new PhoneAppBroadcastReceiver();

    // Broadcast receiver purely for ACTION_MEDIA_BUTTON broadcasts
    private final BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    /** boolean indicating restoring mute state on InCallScreen.onResume() */
    private boolean mShouldRestoreMuteOnInCallResume;

    /**
     * Set the restore mute state flag. Used when we are setting the mute state
     * OUTSIDE of user interaction {@link PhoneUtils#startNewCall(Phone)}
     */
    /*package*/void setRestoreMuteOnInCallResume (boolean mode) {
        mShouldRestoreMuteOnInCallResume = mode;
    }

    /**
     * Get the restore mute state flag.
     * This is used by the InCallScreen {@link InCallScreen#onResume()} to figure
     * out if we need to restore the mute state for the current active call.
     */
    /*package*/boolean getRestoreMuteOnInCallResume () {
        return mShouldRestoreMuteOnInCallResume;
    }

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SIM_LOCKED:
//                    mIsSimPinEnabled = true;
//
//                    if (Config.LOGV) Log.v(LOG_TAG, "show sim unlock panel");
//                    SimPinUnlockPanel pinUnlockPanel = new SimPinUnlockPanel(
//                            PhoneApp.getInstance());
//                    pinUnlockPanel.show();
                    break;

                case EVENT_SIM_ABSENT:
// Don't need this now that the lock screen handles this case
//                    if (Config.LOGV) Log.v(LOG_TAG, "show sim missing panel");
//                    SimMissingPanel missingPanel = new SimMissingPanel(
//                            PhoneApp.getInstance());
//                    missingPanel.show();
                    break;

                case EVENT_SIM_NETWORK_LOCKED:
                    if (Config.LOGV) Log.v(LOG_TAG, "show sim depersonal panel");
                    SimNetworkDepersonalizationPanel ndpPanel =
                        new SimNetworkDepersonalizationPanel(PhoneApp.getInstance());
                    ndpPanel.show();
                    break;

                case EVENT_BLUETOOTH_HEADSET_CONNECTED:
                    Toast.makeText(PhoneApp.this,
                            getResources().getString(R.string.bluetooth_headset_connected),
                            Toast.LENGTH_SHORT).show();
                    break;
                case EVENT_BLUETOOTH_HEADSET_DISCONNECTED:
                    Toast.makeText(PhoneApp.this,
                            getResources().getString(R.string.bluetooth_headset_disconnected),
                            Toast.LENGTH_SHORT).show();
                    break;
                case EVENT_DATA_DISCONNECTED:
                    Intent roaming = new Intent();
                    roaming.setClass(PhoneApp.getInstance(),  DataRoamingReenable.class);
                    roaming.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PhoneApp.getInstance().startActivity(roaming);
                    break;
                case MMI_COMPLETE:
                    onMMIComplete((AsyncResult) msg.obj);
                    break;

                case MMI_CANCEL:
                    PhoneUtils.cancelMmiCode(phone);
                    break;

                case EVENT_WIRED_HEADSET_PLUG:
                    // Since the presence of a wired headset or bluetooth affects the
                    // speakerphone, update the "speaker" state.  We ONLY want to do
                    // this on the wired headset connect / disconnect events for now
                    // though, so we're only triggering on EVENT_WIRED_HEADSET_PLUG.
                    if (!isHeadsetPlugged() &&
                            (mBtHandsfree == null || !mBtHandsfree.isAudioOn())) {
                        // is the state is "not connected", restore the speaker state.
                        PhoneUtils.restoreSpeakerMode(getApplicationContext());
                    }
                    NotificationMgr.getDefault().updateSpeakerNotification();
                    break;

                case EVENT_SIM_STATE_CHANGED:
                    // Marks the event where the SIM goes into ready state.
                    // Right now, this is only used for the PUK-unlocking
                    // process.
                    if (msg.obj.equals(SimCard.INTENT_VALUE_SIM_READY)) {
                        // when the right event is triggered and there
                        // are UI objects in the foreground, we close
                        // them to display the lock panel.
                        if (mPUKEntryActivity != null) {
                            mPUKEntryActivity.finish();
                            mPUKEntryActivity = null;
                        }
                        if (mPUKEntryProgressDialog != null) {
                            mPUKEntryProgressDialog.dismiss();
                            mPUKEntryProgressDialog = null;
                        }
                    }
                    break;
            }
        }
    };

    public PhoneApp() {
        sMe = this;
    }

    @Override
    public void onCreate() {
        if (Config.LOGV) Log.v(LOG_TAG, "onCreate()...");

        ContentResolver resolver = getContentResolver();

        if (phone == null) {
            // Initialize the telephony framework
            PhoneFactory.makeDefaultPhones(this);

            // Get the default phone
            phone = PhoneFactory.getDefaultPhone();

            NotificationMgr.init(this);

            phoneMgr = new PhoneInterfaceManager(this, phone);
            if (getSystemService(Context.BLUETOOTH_SERVICE) != null) {
                mBtHandsfree = new BluetoothHandsfree(this, phone);
                startService(new Intent(this, BluetoothHeadsetService.class));
            } else {
                // Device is not bluetooth capable
                mBtHandsfree = null;
            }

            ringer = new Ringer(phone);

            SoundEffect.init();

            // before registering for phone state changes
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            mKeyguardLock = mKeyguardManager.newKeyguardLock(LOG_TAG);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            notifier = new CallNotifier(this, phone, ringer, mBtHandsfree);

            // register for SIM status
            SimCard sim = phone.getSimCard();
            if (sim != null) {
                if (Config.LOGV) Log.v(LOG_TAG, "register for SIM status");
                sim.registerForAbsent(mHandler, EVENT_SIM_ABSENT, null);
                sim.registerForLocked(mHandler, EVENT_SIM_LOCKED, null);
                sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
            }

            // register for MMI/USSD
            phone.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(phone);

            // Register for misc other intent broadcasts.
            IntentFilter intentFilter =
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intentFilter.addAction(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION);
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
            intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            registerReceiver(mReceiver, intentFilter);

            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            IntentFilter mediaButtonIntentFilter =
                    new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            //
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            mediaButtonIntentFilter.setPriority(1);
            //
            registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);

            //set the default values for the preferences in the phone.
            PreferenceManager.setDefaultValues(this, R.xml.network_setting, false);
            PreferenceManager.setDefaultValues(this, R.xml.call_feature_setting, false);

            // Make sure the audio mode (along with some
            // audio-mode-related state of our own) is initialized
            // correctly, given the current state of the phone.
            switch (phone.getState()) {
                case IDLE:
                    if (DBG) Log.d(LOG_TAG, "Resetting audio state/mode: IDLE");
                    PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_IDLE);
                    PhoneUtils.setAudioMode(this, AudioManager.MODE_NORMAL);
                    break;
                case RINGING:
                    if (DBG) Log.d(LOG_TAG, "Resetting audio state/mode: RINGING");
                    PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_RINGING);
                    PhoneUtils.setAudioMode(this, AudioManager.MODE_RINGTONE);
                    break;
                case OFFHOOK:
                    if (DBG) Log.d(LOG_TAG, "Resetting audio state/mode: OFFHOOK");
                    PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_OFFHOOK);
                    PhoneUtils.setAudioMode(this, AudioManager.MODE_IN_CALL);
                    break;
            }
        }

        // XXX pre-load the SimProvider so that it's ready
        resolver.getType(Uri.parse("content://sim/adn"));

        // start with the default value to set the mute state.
        mShouldRestoreMuteOnInCallResume = false;
   }

    /**
     * Returns the singleton instance of the PhoneApp.
     */
    static PhoneApp getInstance() {
        return sMe;
    }

    Ringer getRinger() {
        return ringer;
    }

    BluetoothHandsfree getBluetoothHandsfree() {
        return mBtHandsfree;
    }

    static Intent createCallLogIntent() {
        Intent  intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType("vnd.android.cursor.dir/calls");
        return intent;
    }

    /**
     * Return the intent used to bring up the in-call screen while a call
     * is already in progress (like when you return to the current call
     * after previously bailing out of the in-call UI.)
     *
     * This intent can only be used from within the Phone app, since the
     * InCallScreen is not exported from our AndroidManifest.
     */
    /* package */ static Intent createInCallIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClassName("com.android.phone", getCallScreenClassName());
        return intent;
    }

    /**
     * Variation of createInCallIntent() that also specifies whether the
     * DTMF dialpad should be initially visible when the InCallScreen
     * comes up.
     */
    /* package */ static Intent createInCallIntent(boolean showDialpad) {
        Intent intent = createInCallIntent();
        intent.putExtra(InCallScreen.SHOW_DIALPAD_EXTRA, showDialpad);
        return intent;
    }

    static String getCallScreenClassName() {
        return InCallScreen.class.getName();
    }

    /**
     * Starts the InCallScreen Activity.
     */
    void displayCallScreen() {
        // if (DBG) Log.d(LOG_TAG, "displayCallScreen()...", new Throwable("stack dump"));
        startActivity(createInCallIntent());
        Profiler.callScreenRequested();
    }

    /**
     * Helper function to check for one special feature of the CALL key:
     * Normally, when the phone is idle, CALL takes you to the call log
     * (see the handler for KEYCODE_CALL in PhoneWindow.onKeyUp().)
     * But if the phone is in use (either off-hook or ringing) we instead
     * handle the CALL button by taking you to the in-call UI.
     *
     * @return true if we intercepted the CALL keypress (i.e. the phone
     *              was in use)
     *
     * @see DialerActivity#onCreate
     */
    boolean handleInCallOrRinging() {
        if (phone.getState() != Phone.State.IDLE) {
            // Phone is OFFHOOK or RINGING.
            if (DBG) Log.v(LOG_TAG,
                           "handleInCallOrRinging: show call screen");
            displayCallScreen();
            return true;
        }
        return false;
    }

    boolean isSimPinEnabled() {
        return mIsSimPinEnabled;
    }

    boolean authenticateAgainstCachedSimPin(String pin) {
        return (mCachedSimPin != null && mCachedSimPin.equals(pin));
    }

    void setCachedSimPin(String pin) {
        mCachedSimPin = pin;
    }

    void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    /**
     * @return true if the in-call UI is running as the foreground
     * activity.  (In other words, from the perspective of the
     * InCallScreen activity, return true between onResume() and
     * onPause().)
     *
     * Note this method will return false if the screen is currently off,
     * even if the InCallScreen *was* in the foreground just before the
     * screen turned off.  (This is because the foreground activity is
     * always "paused" while the screen is off.)
     */
    boolean isShowingCallScreen() {
        if (mInCallScreen == null) return false;
        return mInCallScreen.isForegroundActivity();
    }

    /**
     * Sets the activity responsible for un-PUK-blocking the device
     * so that we may close it when we receive a positive result.
     * mPUKEntryActivity is also used to indicate to the device that
     * we are trying to un-PUK-lock the phone. In other words, iff
     * it is NOT null, then we are trying to unlock and waiting for
     * the SIM to move to READY state.
     *
     * @param activity is the activity to close when PUK has
     * finished unlocking. Can be set to null to indicate the unlock
     * or SIM READYing process is over.
     */
    void setPukEntryActivity(Activity activity) {
        mPUKEntryActivity = activity;
    }

    Activity getPUKEntryActivity() {
        return mPUKEntryActivity;
    }

    /**
     * Sets the dialog responsible for notifying the user of un-PUK-
     * blocking - SIM READYing progress, so that we may dismiss it
     * when we receive a positive result.
     *
     * @param dialog indicates the progress dialog informing the user
     * of the state of the device.  Dismissed upon completion of
     * READYing process
     */
    void setPukEntryProgressDialog(ProgressDialog dialog) {
        mPUKEntryProgressDialog = dialog;
    }

    ProgressDialog getPUKEntryProgressDialog() {
        return mPUKEntryProgressDialog;
    }

    /**
     * Disables the keyguard.  This is used by the phone app to allow
     * interaction with the Phone UI when the keyguard would otherwise be
     * active (like receiving an incoming call while the device is
     * locked.)
     *
     * Any call to this method MUST be followed (eventually)
     * by a corresponding reenableKeyguard() call.
     */
    /* package */ void disableKeyguard() {
        if (DBG) Log.d(LOG_TAG, "disable keyguard");
        // if (DBG) Log.d(LOG_TAG, "disableKeyguard()...", new Throwable("stack dump"));
        mKeyguardLock.disableKeyguard();
    }

    /**
     * Re-enables the keyguard after a previous disableKeyguard() call.
     *
     * Any call to this method MUST correspond to (i.e. be balanced with)
     * a previous disableKeyguard() call.
     */
    /* package */ void reenableKeyguard() {
        if (DBG) Log.d(LOG_TAG, "re-enable keyguard");
        // if (DBG) Log.d(LOG_TAG, "reenableKeyguard()...", new Throwable("stack dump"));
        mKeyguardLock.reenableKeyguard();
    }

    /**
     * Controls how quickly the screen times out.
     *
     * The poke lock controls how long it takes before the screen powers
     * down, and therefore has no immediate effect when the current
     * WakeState (see {@link PhoneApp#requestWakeState}) is FULL.
     * If we're in a state where the screen *is* allowed to turn off,
     * though, the poke lock will determine the timeout interval (long or
     * short).
     *
     * @param shortPokeLock tells the device the timeout duration to use
     * before going to sleep
     * {@link com.android.server.PowerManagerService#SHORT_KEYLIGHT_DELAY}.
     */
    /* package */ void setScreenTimeout(ScreenTimeoutDuration duration) {
        if (DBG) Log.d(LOG_TAG, "setScreenTimeout(" + duration + ")...");

        // make sure we don't set the poke lock repeatedly so that we
        // avoid triggering the userActivity calls in
        // PowerManagerService.setPokeLock().
        if (duration == mPokeLockSetting) {
            return;
        }
        mPokeLockSetting = duration;

        // This is kind of convoluted, but the basic thing to remember is
        // that the poke lock just sends a message to the screen to tell
        // it to stay on for a while.
        // The default is 0, for a long timeout and should be set that way
        // when we are heading back into a the keyguard / screen off
        // state, and also when we're trying to keep the screen alive
        // while ringing.  We'll also want to ignore the cheek events
        // regardless of the timeout duration.
        // The short timeout is really used whenever we want to give up
        // the screen lock, such as when we're in call.
        int pokeLockSetting = LocalPowerManager.POKE_LOCK_IGNORE_CHEEK_EVENTS;
        switch (duration) {
            case SHORT:
                // Set the poke lock to timeout the display after a short
                // timeout (5s). This ensures that the screen goes to sleep
                // as soon as acceptably possible after we the wake lock
                // has been released.
                if (DBG) Log.d(LOG_TAG, "setting short poke lock");
                pokeLockSetting |= LocalPowerManager.POKE_LOCK_SHORT_TIMEOUT;
                break;

            case MEDIUM:
                // Set the poke lock to timeout the display after a medium
                // timeout (15s). This ensures that the screen goes to sleep
                // as soon as acceptably possible after we the wake lock
                // has been released.
                if (DBG) Log.d(LOG_TAG, "setting medium poke lock");
                pokeLockSetting |= LocalPowerManager.POKE_LOCK_MEDIUM_TIMEOUT;
                break;

            case DEFAULT:
            default:
                // set the poke lock to timeout the display after a long
                // delay by default.
                // TODO: it may be nice to be able to disable cheek presses
                // for long poke locks (emergency dialer, for instance).
                if (DBG) Log.d(LOG_TAG, "reverting to normal long poke lock");
                break;
        }

        // Send the request
        try {
            mPowerManagerService.setPokeLock(pokeLockSetting, mPokeLockToken, LOG_TAG);
        } catch (RemoteException e) {
        }
    }

    /**
     * Controls whether or not the screen is allowed to sleep.
     *
     * Once sleep is allowed (WakeState is SLEEP), it will rely on the
     * settings for the poke lock to determine when to timeout and let
     * the device sleep {@link PhoneApp#setScreenTimeout}.
     *
     * @param ws tells the device to how to wake.
     */
    /* package */ void requestWakeState(WakeState ws) {
        if (DBG) Log.d(LOG_TAG, "requestWakeState(" + ws + ")...");
        if (mWakeState != ws) {
            switch (ws) {
                case PARTIAL:
                    // acquire the processor wake lock, and release the FULL
                    // lock if it is being held.
                    if (DBG) Log.d(LOG_TAG, "acquire partial wake lock (CPU only)");
                    mPartialWakeLock.acquire();
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                    break;
                case FULL:
                    // acquire the full wake lock, and release the PARTIAL
                    // lock if it is being held.
                    if (DBG) Log.d(LOG_TAG, "acquire full wake lock (CPU + Screen)");
                    mWakeLock.acquire();
                    if (mPartialWakeLock.isHeld()) {
                        mPartialWakeLock.release();
                    }
                    break;
                case SLEEP:
                default:
                    // release both the PARTIAL and FULL locks.
                    if (DBG) Log.d(LOG_TAG, "release all wake locks");
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                    if (mPartialWakeLock.isHeld()) {
                        mPartialWakeLock.release();
                    }
                    break;
            }
            mWakeState = ws;
        }
    }

    /**
     * If we are not currently keeping the screen on, then poke the power
     * manager to wake up the screen for the user activity timeout duration.
     */
    /* package */ void wakeUpScreen() {
        if (mWakeState == WakeState.SLEEP) {
            if (DBG) Log.d(LOG_TAG, "pulse screen lock");
            try {
                mPowerManagerService.userActivityWithForce(SystemClock.uptimeMillis(), false, true);
            } catch (RemoteException ex) {
                // Ignore -- the system process is dead.
            }
        }
    }

    /**
     * Sets the wake state and screen timeout based on the current state
     * of the phone, and the current state of the in-call UI.
     *
     * This method is a "UI Policy" wrapper around
     * {@link PhoneApp#requestWakeState} and {@link PhoneApp#setScreenTimeout}.
     *
     * It's safe to call this method regardless of the state of the Phone
     * (e.g. whether or not it's idle), and regardless of the state of the
     * Phone UI (e.g. whether or not the InCallScreen is active.)
     */
    /* package */ void updateWakeState() {
        Phone.State state = phone.getState();

        // True if the in-call UI is the foreground activity.
        // (Note this will be false if the screen is currently off,
        // since in that case *no* activity is in the foreground.)
        boolean isShowingCallScreen = isShowingCallScreen();

        // True if the InCallScreen's DTMF dialer is currently opened.
        // (Note this does NOT imply whether or not the InCallScreen
        // itself is visible.)
        boolean isDialerOpened = (mInCallScreen != null) && mInCallScreen.isDialerOpened();

        // True if the speakerphone is in use.  (If so, we *always* use
        // the default timeout.  Since the user is obviously not holding
        // the phone up to his/her face, we don't need to worry about
        // false touches, and thus don't need to turn the screen off so
        // aggressively.)
        // Note that we need to make a fresh call to this method any
        // time the speaker state changes.  (That happens in
        // PhoneUtils.turnOnSpeaker().)
        boolean isSpeakerInUse = (state == Phone.State.OFFHOOK) && PhoneUtils.isSpeakerOn(this);

        // TODO (bug 1440854): The screen timeout *might* also need to
        // depend on the bluetooth state, but this isn't as clear-cut as
        // the speaker state (since while using BT it's common for the
        // user to put the phone straight into a pocket, in which case the
        // timeout should probably still be short.)

        if (DBG) Log.d(LOG_TAG, "updateWakeState: isShowingCallScreen " + isShowingCallScreen
                       + ", isDialerOpened " + isDialerOpened
                       + ", isSpeakerInUse " + isSpeakerInUse + "...");

        if (!isShowingCallScreen || isSpeakerInUse) {
            // Use the system-wide default timeout.
            setScreenTimeout(ScreenTimeoutDuration.DEFAULT);
        } else {
            // Ok, use a special in-call-specific screen timeout value
            // instead of the system-wide default.  (This timeout is very
            // short if the DTMF dialpad is up, and medium otherwise.)
            if (isDialerOpened) {
                setScreenTimeout(ScreenTimeoutDuration.SHORT);
            } else {
                setScreenTimeout(ScreenTimeoutDuration.MEDIUM);
            }
        }

        // Force the screen to be on if the phone is ringing, or if we're
        // displaying the "Call ended" UI for a connection in the
        // "disconnected" state.
        boolean isRinging = (state == Phone.State.RINGING);
        boolean showingDisconnectedConnection =
                PhoneUtils.hasDisconnectedConnections(phone) && isShowingCallScreen;
        boolean keepScreenOn = isRinging || showingDisconnectedConnection;
        if (DBG) Log.d(LOG_TAG, "updateWakeState: keepScreenOn = " + keepScreenOn
                       + " (isRinging " + isRinging
                       + ", showingDisconnectedConnection " + showingDisconnectedConnection + ")");
        // keepScreenOn == true means we'll hold a full wake lock:
        requestWakeState(keepScreenOn ? WakeState.FULL : WakeState.SLEEP);
    }

    /**
     * Wrapper around the PowerManagerService.preventScreenOn() API.
     * This allows the in-call UI to prevent the screen from turning on
     * even if a subsequent call to updateWakeState() causes us to acquire
     * a full wake lock.
     */
    /* package */ void preventScreenOn(boolean prevent) {
        if (DBG) Log.d(LOG_TAG, "- preventScreenOn(" + prevent + ")...");
        try {
            mPowerManagerService.preventScreenOn(prevent);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "mPowerManagerService.preventScreenOn() failed: " + e);
        }
    }

    KeyguardManager getKeyguardManager() {
        return mKeyguardManager;
    }

    private void onMMIComplete(AsyncResult r) {
        if (DBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        PhoneUtils.displayMMIComplete(phone, getInstance(), mmiCode, null, null);
    }

    /**
     * @return true if a wired headset is currently plugged in.
     *
     * @see Intent.ACTION_HEADSET_PLUG (which we listen for in mReceiver.onReceive())
     */
    boolean isHeadsetPlugged() {
        return mIsHeadsetPlugged;
    }


    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class PhoneAppBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean enabled = System.getInt(getContentResolver(),
                        System.AIRPLANE_MODE_ON, 0) == 0;
                phone.setRadioPower(enabled);
            } else if (action.equals(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION)) {
                int state = intent.getIntExtra(BluetoothIntent.HEADSET_STATE,
                        BluetoothHeadset.STATE_ERROR);
                int prevState = intent.getIntExtra(BluetoothIntent.HEADSET_PREVIOUS_STATE,
                        BluetoothHeadset.STATE_ERROR);
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    mHandler.sendMessage(
                            mHandler.obtainMessage(EVENT_BLUETOOTH_HEADSET_CONNECTED, 0));
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    if (prevState == BluetoothHeadset.STATE_CONNECTED) {
                        mHandler.sendMessage(
                                mHandler.obtainMessage(EVENT_BLUETOOTH_HEADSET_DISCONNECTED, 0));
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                if ("DISCONNECTED".equals(intent.getStringExtra(Phone.STATE_KEY))) {
                    String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                    if (Phone.REASON_ROAMING_ON.equals(reason)) {
                        mHandler.sendMessage(mHandler.obtainMessage(EVENT_DATA_DISCONNECTED, 0));
                    }
                }
            } else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                if (DBG) Log.d(LOG_TAG, "mReceiver: ACTION_HEADSET_PLUG");
                if (DBG) Log.d(LOG_TAG, "==> intent: " + intent);
                if (DBG) Log.d(LOG_TAG, "    state: " + intent.getIntExtra("state", 0));
                if (DBG) Log.d(LOG_TAG, "    name: " + intent.getStringExtra("name"));
                mIsHeadsetPlugged = (intent.getIntExtra("state", 0) == 1);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_WIRED_HEADSET_PLUG, 0));
            } else if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                if (DBG) Log.d(LOG_TAG, "mReceiver: ACTION_BATTERY_LOW");
                if (DBG) Log.d(LOG_TAG, "==> intent: " + intent);
                notifier.sendBatteryLow();  // Play a warning tone if in-call
            } else if ((action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) &&
                    (mPUKEntryActivity != null)) {
                // if an attempt to un-PUK-lock the device was made, while we're
                // receiving this state change notification, notify the handler.
                // NOTE: This is ONLY triggered if an attempt to un-PUK-lock has
                // been attempted.
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_SIM_STATE_CHANGED,
                        intent.getStringExtra(SimCard.INTENT_KEY_SIM_STATE)));
            }
        }
    }

    /**
     * Broadcast receiver for the ACTION_MEDIA_BUTTON broadcast intent.
     *
     * This functionality isn't lumped in with the other intents in
     * PhoneAppBroadcastReceiver because we instantiate this as a totally
     * separate BroadcastReceiver instance, since we need to manually
     * adjust its IntentFilter's priority (to make sure we get these
     * intents *before* the media player.)
     */
    private class MediaButtonBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (DBG) Log.d(LOG_TAG,
                           "MediaButtonBroadcastReceiver.onReceive()...  event = " + event);
            if ((event != null)
                && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)
                && (event.getAction() == KeyEvent.ACTION_DOWN)) {

                if (event.getRepeatCount() == 0) {
                    // Mute ONLY on the initial keypress.
                    if (DBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: HEADSETHOOK down!");
                    boolean consumed = PhoneUtils.handleHeadsetHook(phone);
                    if (DBG) Log.d(LOG_TAG, "==> called handleHeadsetHook(), consumed = " + consumed);
                    if (consumed) {
                        if (DBG) Log.d(LOG_TAG, "==> Aborting broadcast!");
                        abortBroadcast();
                    }
                } else if (phone.getState() != Phone.State.IDLE){
                    // Otherwise if the phone is active, then just consume / ignore the event.
                    // If we do not do this, the music player handles the event, which doesn't
                    // make sense to the user.
                    if (DBG) Log.d(LOG_TAG, "==> Phone is busy, aborting broadcast!");
                    abortBroadcast();
                }
            }
        }
    }
}
