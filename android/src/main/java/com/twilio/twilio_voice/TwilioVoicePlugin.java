package com.twilio.twilio_voice;

import androidx.annotation.NonNull;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.UnregistrationListener;
import com.twilio.voice.Voice;

import java.util.HashMap;
import java.util.Map;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.Equalizer;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import tvo.webrtc.voiceengine.WebRtcAudioUtils;

public class TwilioVoicePlugin implements FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, ActivityAware, PluginRegistry.NewIntentListener {

    private static final String CHANNEL_NAME = "twilio_voice";
    private static final String TAG = "TwilioVoicePlugin";
    public static final String TwilioPreferences = "com.twilio.twilio_voicePreferences";
    private static final int MIC_PERMISSION_REQUEST_CODE = 1;
    static boolean hasStarted = false;

    private String accessToken;
    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_INVALID;
    private int savedVolumeControlStream;

    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    private NotificationManager notificationManager;
    private CallInvite activeCallInvite;
    private Call activeCall;
    private int activeCallNotificationId;
    private Context context;
    private Activity activity;

    RegistrationListener registrationListener = registrationListener();
    UnregistrationListener unregistrationListener = unregistrationListener();
    Call.Listener callListener = callListener();
    CustomAudioDevice customAudioDevice;
    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private EventChannel.EventSink eventSink;
    private String fcmToken;
    private boolean callOutgoing;
    private boolean backgroundCallUI = false;

    private SharedPreferences pSharedPref;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        register(flutterPluginBinding.getBinaryMessenger(), this, flutterPluginBinding.getApplicationContext());
        hasStarted = true;

        /*
         * Create custom audio device FileAndMicAudioDevice and set the audio device
         */
        customAudioDevice = new CustomAudioDevice(context);
        Voice.setAudioDevice(customAudioDevice);
    }

    private static void register(BinaryMessenger messenger, TwilioVoicePlugin plugin, Context context) {
        Log.d(TAG, "register(BinaryMessenger");
        plugin.methodChannel = new MethodChannel(messenger, CHANNEL_NAME + "/messages");
        plugin.methodChannel.setMethodCallHandler(plugin);

        plugin.eventChannel = new EventChannel(messenger, CHANNEL_NAME + "/events");
        plugin.eventChannel.setStreamHandler(plugin);

        plugin.context = context;

        plugin.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        plugin.voiceBroadcastReceiver = new VoiceBroadcastReceiver(plugin);
        // plugin.registerReceiver();

        /*
         * Needed for setting/abandoning audio focus during a call
         */
        plugin.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        plugin.audioManager.setSpeakerphoneOn(false);

        plugin.pSharedPref = context.getSharedPreferences(TwilioPreferences, Context.MODE_PRIVATE);

    }

    private void handleIncomingCallIntent(Intent intent) {
        Log.d(TAG, "handleIncomingCallIntent");
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "Handling incoming call intent for action " + action);
            activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            callOutgoing = false;

            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                    handleIncomingCall(activeCallInvite.getFrom(), activeCallInvite.getTo());
                    if (Build.VERSION.SDK_INT >= 29 && !isAppVisible()) {
                        break;
                    }
                    startAnswerActivity(activeCallInvite, activeCallNotificationId);
                    break;
                case Constants.ACTION_CANCEL_CALL:
                    handleCancel();
                    break;
                case Constants.ACTION_REJECT:
                    handleReject();
                    break;
                case Constants.ACTION_ACCEPT:
                    int acceptOrigin = intent.getIntExtra(Constants.ACCEPT_CALL_ORIGIN, 0);
                    if (acceptOrigin == 0) {
                        Intent answerIntent = new Intent(activity, AnswerJavaActivity.class);
                        answerIntent.setAction(Constants.ACTION_ACCEPT);
                        answerIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, activeCallNotificationId);
                        answerIntent.putExtra(Constants.INCOMING_CALL_INVITE, activeCallInvite);
                        answerIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        answerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(answerIntent);
                    } else {
                        answer();
                    }

                    break;
                case Constants.ACTION_TOGGLE_MUTE:
                    if (activeCall != null) {
                        boolean muted = activeCall.isMuted();
                        mute(!muted);
                    }
                    break;
                case Constants.ACTION_END_CALL:
                    backgroundCallUI = false;
                    disconnect();
                    break;
                case Constants.ACTION_RETURN_CALL:

                    if (this.checkPermissionForMicrophone()) {
                        final HashMap<String, String> params = new HashMap<>();

                        String to = intent.getStringExtra(Constants.CALL_FROM);
                        String from = intent.getStringExtra(Constants.CALL_TO);
                        Log.d(TAG, "calling: " + to);
                        params.put("To", to.replace("client:", ""));
                        sendPhoneCallEvents("ReturningCall|" + from + "|" + to + "|" + "Incoming");
                        this.callOutgoing = true;
                        final ConnectOptions connectOptions = new ConnectOptions.Builder(this.accessToken).params(params).build();
                        this.activeCall = Voice.connect(this.activity, connectOptions, this.callListener);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void startAnswerActivity(CallInvite callInvite, int notificationId) {
        Intent intent = new Intent(activity, AnswerJavaActivity.class);
        intent.setAction(Constants.ACTION_INCOMING_CALL);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    private void handleIncomingCall(String from, String to) {
        sendPhoneCallEvents("Ringing|" + from + "|" + to + "|" + "Incoming" + formatCustomParams(activeCallInvite.getCustomParameters()));

    }

    private String formatCustomParams(Map<String, String> customParameters) {
        if (!customParameters.isEmpty()) {
            JSONObject json = new JSONObject(customParameters);
            return "|" + json.toString();
        }
        return "";
    }

    private void handleReject() {
        sendPhoneCallEvents("LOG|Call Rejected");

    }

    private void handleCancel() {
        callOutgoing = false;
        sendPhoneCallEvents("Missed Call");
        sendPhoneCallEvents("Call Ended");

        Intent intent = new Intent(activity, AnswerJavaActivity.class);
        intent.setAction(Constants.ACTION_CANCEL_CALL);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);

    }

    private void registerReceiver() {
        if (!isReceiverRegistered) {
            Log.d(TAG, "registerReceiver");
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL);
            intentFilter.addAction(Constants.ACTION_CANCEL_CALL);
            intentFilter.addAction(Constants.ACTION_ACCEPT);
            intentFilter.addAction(Constants.ACTION_REJECT);
            intentFilter.addAction(Constants.ACTION_END_CALL);
            intentFilter.addAction(Constants.ACTION_TOGGLE_MUTE);
            intentFilter.addAction(Constants.ACTION_RETURN_CALL);
            LocalBroadcastManager.getInstance(this.activity).registerReceiver(voiceBroadcastReceiver, intentFilter);
            isReceiverRegistered = true;
        }
    }

    private void unregisterReceiver() {
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(voiceBroadcastReceiver);
            isReceiverRegistered = false;
        }
    }

    private RegistrationListener registrationListener() {
        return new RegistrationListener() {
            @Override
            public void onRegistered(String accessToken, String fcmToken) {
                Log.d(TAG, "Successfully registered FCM " + fcmToken);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                String message = String.format("Registration Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
            }
        };
    }

    private UnregistrationListener unregistrationListener() {
        return new UnregistrationListener() {
            @Override
            public void onUnregistered(String accessToken, String fcmToken) {
                Log.d(TAG, "Successfully un-registered FCM " + fcmToken);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                String message = String.format("Unregistration Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
            }
        };
    }

    private static class VoiceBroadcastReceiver extends BroadcastReceiver {

        private final TwilioVoicePlugin plugin;

        private VoiceBroadcastReceiver(TwilioVoicePlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast for action " + action);

            if (action != null) switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                case Constants.ACTION_CANCEL_CALL:
                case Constants.ACTION_REJECT:
                case Constants.ACTION_ACCEPT:
                case Constants.ACTION_TOGGLE_MUTE:
                case Constants.ACTION_END_CALL:
                case Constants.ACTION_RETURN_CALL:

                    /*
                     * Handle the incoming or cancelled call invite
                     */
                    plugin.handleIncomingCallIntent(intent);
                    break;
                default:
                    Log.d(TAG, "Received broadcast for other action " + action);
                    break;

            }
        }
    }

    /*
     * Register your FCM token with Twilio to receive incoming call invites
     *
     * If a valid google-services.json has not been provided or the
     * FirebaseInstanceId has not been
     * initialized the fcmToken will be null.
     *
     * In the case where the FirebaseInstanceId has not yet been initialized the
     * VoiceFirebaseInstanceIDService.onTokenRefresh should result in a
     * LocalBroadcast to this
     * activity which will attempt registerForCallInvites again.
     *
     */
    private void registerForCallInvites() {
        if (this.accessToken != null && this.fcmToken != null) {
            Log.i(TAG, "Registering with FCM");
            Voice.register(this.accessToken, Voice.RegistrationChannel.FCM, this.fcmToken, registrationListener);
        }
    }

    private void unregisterForCallInvites(String accessToken) {
        if (this.fcmToken == null) {
            return;
        }
        Log.i(TAG, "Un-registering with FCM");
        if (accessToken != null) {
            Voice.unregister(accessToken, Voice.RegistrationChannel.FCM, this.fcmToken, unregistrationListener);
        } else if (this.accessToken != null) {
            Voice.unregister(this.accessToken, Voice.RegistrationChannel.FCM, this.fcmToken, unregistrationListener);
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "Detatched from Flutter engine");
        SoundPoolManager.getInstance(context).release();
        context = null;
        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
        eventChannel.setStreamHandler(null);
        eventChannel = null;
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        Log.i(TAG, "Setting event sink");
        this.eventSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
        Log.i(TAG, "Removing event sink");
        this.eventSink = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("tokens")) {
            Log.d(TAG, "Setting up tokens");
            this.accessToken = call.argument("accessToken");
            this.fcmToken = call.argument("deviceToken");
            this.registerForCallInvites();
            result.success(true);
        } else if (call.method.equals("sendDigits")) {
            String digits = call.argument("digits");
            if (this.activeCall != null) {
                Log.d(TAG, "Sending digits " + digits);
                this.activeCall.sendDigits(digits);
            }
            result.success(true);
        } else if (call.method.equals("hangUp")) {
            Log.d(TAG, "Hanging up");
            this.disconnect();
            result.success(true);
        } else if (call.method.equals("toggleSpeaker")) {

            boolean speakerIsOn = call.argument("speakerIsOn");
            // if(speakerIsOn == null) return;
            audioManager.setSpeakerphoneOn(speakerIsOn);
            sendPhoneCallEvents(speakerIsOn ? "Speaker On" : "Speaker Off");

            result.success(true);
        } else if (call.method.equals("toggleMute")) {
            boolean muted = call.argument("muted");
            Log.d(TAG, "Muting call");
            this.mute(muted);
            result.success(true);
        } else if (call.method.equals("call-sid")) {
            result.success(activeCall == null ? null : activeCall.getSid());
        } else if (call.method.equals("isOnCall")) {
            Log.d(TAG, "Is on call invoked");
            result.success(this.activeCall != null);
        } else if (call.method.equals("holdCall")) {
            Log.d(TAG, "Hold call invoked");
            this.hold();
            result.success(true);
        } else if (call.method.equals("answer")) {
            Log.d(TAG, "Answering call");
            this.answer();
            result.success(true);
        } else if (call.method.equals("unregister")) {
            String accessToken = call.argument("accessToken");
            this.unregisterForCallInvites(accessToken);
            result.success(true);
        } else if (call.method.equals("makeCall")) {
            Log.d(TAG, "Making new call");
            sendPhoneCallEvents("LOG|Making new call");
            final HashMap<String, String> params = new HashMap<>();
            Map<String, Object> args = call.arguments();

            for (Map.Entry<String, Object> entry : args.entrySet()) {

                String key = entry.getKey();
                Object value = entry.getValue();
                if (!key.equals("From") && value != null) {
                    params.put(key, value.toString());
                }
            }

            this.callOutgoing = true;
            final ConnectOptions connectOptions = new ConnectOptions.Builder(this.accessToken).params(params).build();
            Log.d(TAG, "calling to " + call.argument("To").toString());

            this.activeCall = Voice.connect(this.activity, connectOptions, this.callListener);

            result.success(true);

        } else if (call.method.equals("registerClient")) {
            String id = call.argument("id");
            String name = call.argument("name");
            boolean added = false;
            if (id != null && name != null) {
                sendPhoneCallEvents("LOG|Registering client " + id + ":" + name);
                SharedPreferences.Editor edit = pSharedPref.edit();
                edit.putString(id, name);
                edit.apply();
                added = true;
            }
            result.success(added);
        } else if (call.method.equals("unregisterClient")) {
            String id = call.argument("id");
            boolean added = false;
            if (id != null) {
                sendPhoneCallEvents("LOG|Unregistering" + id);
                SharedPreferences.Editor edit = pSharedPref.edit();
                edit.remove(id);
                edit.apply();
                added = true;
            }
            result.success(added);
        } else if (call.method.equals("defaultCaller")) {
            String caller = call.argument("defaultCaller");
            boolean added = false;
            if (caller != null) {
                sendPhoneCallEvents("LOG|defaultCaller is " + caller);
                SharedPreferences.Editor edit = pSharedPref.edit();
                edit.putString("defaultCaller", caller);
                edit.apply();
                added = true;
            }
            result.success(added);
        } else if (call.method.equals("hasMicPermission")) {
            result.success(this.checkPermissionForMicrophone());
        } else if (call.method.equals("requestMicPermission")) {
            sendPhoneCallEvents("LOG|requesting mic permission");
            if (!this.checkPermissionForMicrophone()) {
                boolean hasAccess = this.requestPermissionForMicrophone();
                result.success(hasAccess);
            } else {
                result.success(true);
            }
        } else if (call.method.equals("backgroundCallUI")) {
            if (activeCall != null) {
                Intent intent = new Intent(activity, BackgroundCallJavaActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(Constants.CALL_FROM, activeCall.getFrom());
                activity.startActivity(intent);
                backgroundCallUI = true;
            }

        } else if (call.method.equals("show-notifications")) {
            boolean show = call.argument("show");
            boolean prefsShow = pSharedPref.getBoolean("show-notifications", true);
            if (show != prefsShow) {
                SharedPreferences.Editor edit = pSharedPref.edit();
                edit.putBoolean("show-notifications", show);
                edit.apply();
            }
        } else if (call.method.equals("requiresBackgroundPermissions")) {
            String manufacturer = "xiaomi";
            if (manufacturer.equalsIgnoreCase(android.os.Build.MANUFACTURER)) {
                result.success(true);
                return;
            }
            result.success(false);
        } else if (call.method.equals("requestBackgroundPermissions")) {
            String manufacturer = "xiaomi";
            if (manufacturer.equalsIgnoreCase(android.os.Build.MANUFACTURER)) {

                Intent localIntent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                localIntent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
                localIntent.putExtra("extra_pkgname", activity.getPackageName());
                activity.startActivity(localIntent);
            }
            result.success(true);
        } else {
            result.notImplemented();
        }
    }

    /*
     * Accept an incoming Call
     */
    private void answer() {
        Log.d(TAG, "Answering call");

        activeCallInvite.accept(this.activity, callListener);
        sendPhoneCallEvents("Answer|" + activeCallInvite.getFrom() + "|" + activeCallInvite.getTo() + formatCustomParams(activeCallInvite.getCustomParameters()));
        notificationManager.cancel(activeCallNotificationId);
    }

    private void sendPhoneCallEvents(String description) {
        if (eventSink == null) {
            return;
        }
        eventSink.success(description);
    }

    @Override
    public boolean onNewIntent(Intent intent) {
        Log.d(TAG, "onNewIntent");
        this.handleIncomingCallIntent(intent);
        return false;
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding activityPluginBinding) {
        Log.d(TAG, "onAttachedToActivity");
        this.activity = activityPluginBinding.getActivity();
        activityPluginBinding.addOnNewIntentListener(this);
        registerReceiver();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges");
        unregisterReceiver();
        this.activity = null;

    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges");
        this.activity = activityPluginBinding.getActivity();
        activityPluginBinding.addOnNewIntentListener(this);
        registerReceiver();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
        unregisterReceiver();
        this.activity = null;
    }

    Call.Listener callListener() {
        return new Call.Listener() {
            /*
             * This callback is emitted once before the Call.Listener.onConnected() callback
             * when
             * the callee is being alerted of a Call. The behavior of this callback is
             * determined by
             * the answerOnBridge flag provided in the Dial verb of your TwiML application
             * associated with this client. If the answerOnBridge flag is false, which is
             * the
             * default, the Call.Listener.onConnected() callback will be emitted immediately
             * after
             * Call.Listener.onRinging(). If the answerOnBridge flag is true, this will
             * cause the
             * call to emit the onConnected callback only after the call is answered.
             * See answeronbridge for more details on how to use it with the Dial TwiML
             * verb. If the
             * twiML response contains a Say verb, then the call will emit the
             * Call.Listener.onConnected callback immediately after
             * Call.Listener.onRinging() is
             * raised, irrespective of the value of answerOnBridge being set to true or
             * false
             */
            @Override
            public void onRinging(Call call) {
                Log.d(TAG, "onRinging");
                sendPhoneCallEvents("Ringing|" + call.getFrom() + "|" + call.getTo() + "|" + (callOutgoing ? "Outgoing" : "Incoming"));
            }

            @Override
            public void onConnectFailure(Call call, CallException error) {
                setAudioFocus(false);
                Log.d(TAG, "Connect failure");
                String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
                sendPhoneCallEvents("LOG|" + message);

            }

            @Override
            public void onConnected(Call call) {
                setAudioFocus(true);
                Log.d(TAG, "onConnected");
                activeCall = call;


                /*
                 * Enable changing the volume using the up/down keys during a conversation
                 */
                savedVolumeControlStream = activity.getVolumeControlStream();
                activity.setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
                sendPhoneCallEvents("Connected|" + call.getFrom() + "|" + call.getTo() + "|" + (callOutgoing ? "Outgoing" : "Incoming"));
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                Log.d(TAG, "onReconnecting");
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.d(TAG, "onReconnected");
            }

            @Override
            public void onDisconnected(Call call, CallException error) {
                setAudioFocus(false);
                Log.d(TAG, "Disconnected");
                if (error != null) {
                    String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                    Log.e(TAG, message);
                }
                activity.setVolumeControlStream(savedVolumeControlStream);
                sendPhoneCallEvents("Call Ended");
                disconnected();
            }
        };

    }

    private void disconnect() {
        if (activeCall != null) {
            activeCall.disconnect();
            disconnected();
        }
    }

    private void disconnected() {

        if (activeCall == null) return;

        if (backgroundCallUI) {
            Intent intent = new Intent(activity, BackgroundCallJavaActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Constants.ACTION_CANCEL_CALL);

            activity.startActivity(intent);
        }
        SoundPoolManager.getInstance(context).playDisconnect();
        backgroundCallUI = false;
        callOutgoing = false;
        activeCall = null;

    }

    private void hold() {
        if (activeCall != null) {
            boolean hold = activeCall.isOnHold();
            activeCall.hold(!hold);
            sendPhoneCallEvents(hold ? "Unhold" : "Hold");
        }
    }

    private void mute(boolean muted) {
        if (activeCall != null) {
            activeCall.mute(muted);
            sendPhoneCallEvents(muted ? "Mute" : "Unmute");
        }
    }

    private void setAudioFocus(boolean setFocus) {
        if (audioManager != null) {
            sendPhoneCallEvents("LOG|setting audio focus => setFocus:" + setFocus);
            if (setFocus) {
                // While we are at it set the stream volume too
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);

                // Set audio effect here for now
                enableAudioEffects();

                savedAudioMode = audioManager.getMode();
                sendPhoneCallEvents("LOG|saveAudioMode =>:" + savedAudioMode);

                // Request audio focus before making any device switch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sendPhoneCallEvents("LOG|inside if");
                    AudioAttributes playbackAttributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                    AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(playbackAttributes).setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int i) {
                            sendPhoneCallEvents("LOG|audio focus change =>:" + i);
                        }
                    }).build();
                    int result = audioManager.requestAudioFocus(focusRequest);
                    sendPhoneCallEvents("LOG|request result =>:" + result);

                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        sendPhoneCallEvents("LOG|request result granted:");
                        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

                    }
                } else {
                    sendPhoneCallEvents("LOG|inside else");

                    audioManager.requestAudioFocus(focusChange -> {
                        sendPhoneCallEvents("LOG|inside if" + focusChange);
                    }, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
                /*
                 * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
                 * required to be in this mode when playout and/or recording starts for
                 * best possible VoIP performance. Some devices have difficulties with speaker
                 * mode
                 * if this is not set.
                 */
                if (audioManager.isBluetoothA2dpOn() || audioManager.isBluetoothScoOn()) {
                    audioManager.startBluetoothSco();
                    audioManager.setBluetoothScoOn(true);

                }
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
                audioManager.setMode(savedAudioMode);
                audioManager.abandonAudioFocus(null);
            }
        }
        sendPhoneCallEvents("LOG|null audio focus => setFocus:" + setFocus);

    }

    private boolean checkPermissionForMicrophone() {
        sendPhoneCallEvents("LOG|checkPermissionForMicrophone");
        int resultMic = ContextCompat.checkSelfPermission(this.context, Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private boolean requestPermissionForMicrophone() {
        sendPhoneCallEvents("LOG|requestPermissionForMicrophone");
        if (ActivityCompat.shouldShowRequestPermissionRationale(this.activity, Manifest.permission.RECORD_AUDIO)) {
            sendPhoneCallEvents("RequestMicrophoneAccess");
            return false;
        } else {
            ActivityCompat.requestPermissions(this.activity, new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST_CODE);
            return true;
        }
    }

    private boolean isAppVisible() {
        return ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private void enableAudioEffects() {
        int audioSessionId = customAudioDevice.audioSessionId;
        int recordSessionId = customAudioDevice.recordAudioSessionId;
        Log.v(TAG, "Attempting to enable audio effects for session ID: " + audioSessionId + " and for record session ID: " + recordSessionId);

        if (AcousticEchoCanceler.isAvailable()) {
            Log.v(TAG, "HW-Based Acoustic Echo Canceler is available. Creating instance...");
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(recordSessionId);
            if (aec != null) {
                try {
                    Log.v(TAG, "HW-Based Acoustic Echo Canceler created successfully. Enabling...");
                    aec.setEnabled(true);
                    Log.i(TAG, "HW-Based Acoustic Echo Canceler enabled");
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error enabling HW-Based Acoustic Echo Canceler", e);
                    WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
                    Log.i(TAG, "WebRTC-based Acoustic Echo Canceler set");
                }
            } else {
                Log.e(TAG, "Failed to create HW-Based Acoustic Echo Canceler");
                WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
                Log.i(TAG, "WebRTC-based Acoustic Echo Canceler set");
            }
        } else {
            Log.e(TAG, "HW-Based Acoustic Echo Canceler is not available on this device");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
            Log.i(TAG, "WebRTC-based Acoustic Echo Canceler set");
        }

        if (NoiseSuppressor.isAvailable()) {
            Log.v(TAG, "HW-Based Noise Suppressor is available. Creating instance...");
            NoiseSuppressor ns = NoiseSuppressor.create(recordSessionId);
            if (ns != null) {
                try {
                    Log.v(TAG, "HW-Based Noise Suppressor created successfully. Enabling...");
                    ns.setEnabled(true);
                    Log.i(TAG, "HW-Based Noise Suppressor enabled");
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error enabling HW-Based Noise Suppressor", e);
                    WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
                    Log.i(TAG, "WebRTC-based Noise Suppressor set");
                }
            } else {
                Log.e(TAG, "Failed to create HW-Based Noise Suppressor");
                WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
                Log.i(TAG, "WebRTC-based Noise Suppressor set");
            }
        } else {
            Log.e(TAG, "HW-Based Noise Suppressor is not available on this device");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
            Log.i(TAG, "WebRTC-based Noise Suppressor set");
        }

        if (AutomaticGainControl.isAvailable()) {
            Log.v(TAG, "HW-Based Automatic Gain Control is available. Creating instance...");
            AutomaticGainControl agc = AutomaticGainControl.create(recordSessionId);
            if (agc != null) {
                try {
                    Log.v(TAG, "HW-Based Automatic Gain Control created successfully. Enabling...");
                    agc.setEnabled(true);
                    Log.i(TAG, "HW-Based Automatic Gain Control enabled");
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error enabling HW-Based Automatic Gain Control", e);
                    WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
                    Log.i(TAG, "WebRTC-based Automatic Gain Control set");
                }
            } else {
                Log.e(TAG, "Failed to create HW-Based Automatic Gain Control");
                WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
                Log.i(TAG, "WebRTC-based Automatic Gain Control set");
            }
        } else {
            Log.e(TAG, "HW-Based Automatic Gain Control is not available on this device");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
            Log.i(TAG, "WebRTC-based Automatic Gain Control set");
        }

        Equalizer equalizer = new Equalizer(0, audioSessionId);
        try {
            equalizer.setEnabled(true);
            Log.i(TAG, "Equalizer enabled");

            // Get the number of bands and the level range
            short bandCount = equalizer.getNumberOfBands();
            short[] bandLevels = new short[bandCount];
            short maxBandLevel = equalizer.getBandLevelRange()[1];
            
            Log.v(TAG, "Current Equalizer band levels:");
            for (short band = 0; band < bandCount; band++) {
                // Log the current levels for each band
                bandLevels[band] = equalizer.getBandLevel(band);
                Log.v(TAG, "Band " + band + " Hz, Level = " + bandLevels[band] + " mB");

                // Boost each band to max
                equalizer.setBandLevel(band, maxBandLevel);
                // Log the new levels for each band
                Log.v(TAG, "Band " + band + " Hz, New Level = " + maxBandLevel + " mB");
            }
            Log.i(TAG, "Equalizer band levels are adjusted to " + maxBandLevel);

        } catch (RuntimeException e) {
            Log.e(TAG, "Error enabling Equalizer", e);
        }
    }

}
