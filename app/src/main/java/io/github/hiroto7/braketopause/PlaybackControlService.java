package io.github.hiroto7.braketopause;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PlaybackControlService extends Service implements AudioManager.OnAudioFocusChangeListener {

    private static final int NOTIFICATION_ID = 1;
    private static final int DELAY_MILLIS = 60 * 30 * 1000;
    private static final String TAG = PlaybackControlService.class.getSimpleName();
    private static final String ACTION_TRANSITION = PlaybackControlService.class.getCanonicalName() + ".ACTION_TRANSITION";
    private static final String ACTION_STOP_MEDIA_CONTROL = PlaybackControlService.class.getCanonicalName() + ".ACTION_STOP_MEDIA_CONTROL";
    private static final String PLAYBACK_CONTROL_CHANNEL_ID = "playback_control";
    private static final String AUTOMATIC_STOP_CHANNEL_ID = "automatic_stop";
    private final AudioFocusRequest focusRequest =
            new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setOnAudioFocusChangeListener(this)
                    .build();
    private final IBinder binder = new MediaControlBinder();
    private final Handler handler = new Handler();
    private SharedPreferences sharedPreferences;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private AudioManager audioManager;
    private NotificationManager notificationManager;
    private ActivityRecognitionClient activityRecognitionClient;
    private boolean enabled = false;
    private boolean usesLocation;
    private boolean usesActivityRecognition;
    private boolean hasAudioFocus = false;
    private boolean timerInProgress = false;
    private List<Integer> selectedActivities;
    private PendingIntent mainPendingIntent;
    private Notification controllingPlaybackNotification;
    private Notification playbackPausedNotification;
    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            super.onLocationResult(locationResult);
            Location location = locationResult.getLastLocation();

            if (!location.hasSpeed()) {
                return;
            }

            int speedThresholdKph = sharedPreferences.getInt(getString(R.string.speed_threshold_key), 8);

            float lastSpeedMps = location.getSpeed();
            float lastSpeedKph = 3.6f * lastSpeedMps;

            if (lastSpeedKph < speedThresholdKph) {
                pausePlayback();
            } else {
                resumePlayback();
            }
        }
    };
    private final BroadcastReceiver transitionsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ActivityTransitionResult.hasResult(intent)) {
                return;
            }

            ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);

            for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                if (selectedActivities.contains(event.getActivityType()) && event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    if (usesLocation) {
                        requestLocationUpdates();
                    } else {
                        resumePlayback();
                    }
                } else {
                    if (usesLocation) {
                        removeLocationUpdates();
                    }

                    pausePlayback();
                }
            }
        }
    };
    private PendingIntent transitionPendingIntent;
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            stopMediaControl();
            stopSelf();
        }
    };
    private final Runnable stopMediaControlWithNotificationCallback = () -> {
        stopMediaControl();

        final Notification notification = new NotificationCompat.Builder(this, AUTOMATIC_STOP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_stop_24)
                .setContentTitle(getString(R.string.playback_control_automatically_ended))
                .setContentText(getString(R.string.time_has_passed_with_media_paused))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(mainPendingIntent)
                .setAutoCancel(true)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    };

    public boolean isEnabled() {
        return enabled;
    }

    private void startTimer() {
        handler.postDelayed(stopMediaControlWithNotificationCallback, DELAY_MILLIS);
        timerInProgress = true;
    }

    private void stopTimer() {
        handler.removeCallbacks(stopMediaControlWithNotificationCallback);
        timerInProgress = false;
    }

    private void pausePlayback() {
        if (!hasAudioFocus) {
            audioManager.requestAudioFocus(focusRequest);
            hasAudioFocus = true;
            notificationManager.notify(NOTIFICATION_ID, playbackPausedNotification);
        }

        if (!timerInProgress) {
            startTimer();
        }
    }

    private void resumePlayback() {
        if (hasAudioFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            hasAudioFocus = false;
            notificationManager.notify(NOTIFICATION_ID, controllingPlaybackNotification);
        }

        if (timerInProgress) {
            stopTimer();
        }
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(1000);
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void removeLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback);
    }

    private final Set<Runnable> onMediaControlStartedListeners = new HashSet<>();

    private void requestActivityTransitionUpdates() {
        registerReceiver(transitionsReceiver, new IntentFilter(ACTION_TRANSITION));

        final List<Integer> activityTypes = Arrays.asList(
                DetectedActivity.STILL,
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.RUNNING,
                DetectedActivity.WALKING);
        final List<ActivityTransition> transitions = activityTypes.stream()
                .map(activityType -> new ActivityTransition.Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                        .build())
                .collect(Collectors.toList());

        final ActivityTransitionRequest request = new ActivityTransitionRequest(transitions);
        final Task<Void> task = activityRecognitionClient.requestActivityTransitionUpdates(request, transitionPendingIntent);
    }

    private void removeActivityTransitionUpdates() {
        unregisterReceiver(transitionsReceiver);
        activityRecognitionClient.removeActivityTransitionUpdates(transitionPendingIntent);
    }

    private final Set<Runnable> onMediaControlStoppedListeners = new HashSet<>();

    private void createNotificationChannel() {
        final List<NotificationChannel> channels = Arrays.asList(
                new NotificationChannel(PLAYBACK_CONTROL_CHANNEL_ID, getString(R.string.playback_control), NotificationManager.IMPORTANCE_LOW),
                new NotificationChannel(AUTOMATIC_STOP_CHANNEL_ID, getString(R.string.automatic_exit), NotificationManager.IMPORTANCE_DEFAULT));
        notificationManager.createNotificationChannels(channels);
    }

    public void stopMediaControl() {
        unregisterReceiver(stopReceiver);
        stopForeground(true);

        if (timerInProgress) {
            stopTimer();
        }

        if (usesLocation) {
            removeLocationUpdates();
        }

        if (usesActivityRecognition) {
            removeActivityTransitionUpdates();
        }

        enabled = false;
        onMediaControlStoppedListeners.forEach(Runnable::run);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (hasAudioFocus) {
            final AudioFocusRequest lastingFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build();
            audioManager.requestAudioFocus(lastingFocusRequest);
        }

        if (enabled) {
            stopMediaControl();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange != AudioManager.AUDIOFOCUS_LOSS) {
            return;
        }

        hasAudioFocus = false;

        if (!enabled) {
            return;
        }

        notificationManager.notify(NOTIFICATION_ID, controllingPlaybackNotification);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = getSystemService(AudioManager.class);
        notificationManager = getSystemService(NotificationManager.class);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        activityRecognitionClient = ActivityRecognition.getClient(this);

        final Intent transitionIntent = new Intent(ACTION_TRANSITION);
        transitionPendingIntent = PendingIntent.getBroadcast(this, 0, transitionIntent,
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE :
                        PendingIntent.FLAG_UPDATE_CURRENT);

        final Intent mainIntent = new Intent(this, MainActivity.class);
        mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final Intent stopIntent = new Intent(ACTION_STOP_MEDIA_CONTROL);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, PLAYBACK_CONTROL_CHANNEL_ID)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(mainPendingIntent)
                .setColorized(true)
                .setColor(Color.GRAY)
                .addAction(R.drawable.ic_baseline_stop_24, getString(R.string.finish_playback_control), stopPendingIntent);

        controllingPlaybackNotification = notificationBuilder
                .setContentTitle(getString(R.string.controlling_playback_state))
                .setSmallIcon(R.drawable.ic_baseline_pause_circle_outline_24)
                .build();

        playbackPausedNotification = notificationBuilder
                .setContentTitle(getText(R.string.paused_media))
                .setSmallIcon(R.drawable.ic_baseline_pause_24)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        createNotificationChannel();

        startForeground(NOTIFICATION_ID, hasAudioFocus ? playbackPausedNotification : controllingPlaybackNotification);
        startTimer();
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP_MEDIA_CONTROL));

        usesLocation = sharedPreferences.getBoolean(getString(R.string.location_key), true);
        usesActivityRecognition = sharedPreferences.getBoolean(getString(R.string.activity_recognition_key), true);

        selectedActivities = new LinkedList<>();
        if (sharedPreferences.getBoolean(getString(R.string.in_vehicle_key), true)) {
            selectedActivities.add(DetectedActivity.IN_VEHICLE);
        }
        if (sharedPreferences.getBoolean(getString(R.string.on_bicycle_key), true)) {
            selectedActivities.add(DetectedActivity.ON_BICYCLE);
        }
        if (sharedPreferences.getBoolean(getString(R.string.running_key), true)) {
            selectedActivities.add(DetectedActivity.RUNNING);
        }
        if (sharedPreferences.getBoolean(getString(R.string.walking_key), true)) {
            selectedActivities.add(DetectedActivity.WALKING);
        }

        if (usesLocation) {
            requestLocationUpdates();
        }

        if (usesActivityRecognition) {
            requestActivityTransitionUpdates();
        }

        enabled = true;
        onMediaControlStartedListeners.forEach(Runnable::run);

        return START_STICKY;
    }

    public void addOnMediaControlStartedListener(@NonNull Runnable onMediaControlStartedListener) {
        this.onMediaControlStartedListeners.add(onMediaControlStartedListener);
    }

    public void removeOnMediaControlStartedListener(@NonNull Runnable onMediaControlStartedListener) {
        this.onMediaControlStartedListeners.remove(onMediaControlStartedListener);
    }

    public void addOnMediaControlStoppedListener(@NonNull Runnable onMediaControlStoppedListener) {
        this.onMediaControlStoppedListeners.add(onMediaControlStoppedListener);
    }

    public void removeOnMediaControlStoppedListener(@NonNull Runnable onMediaControlStoppedListener) {
        this.onMediaControlStoppedListeners.remove(onMediaControlStoppedListener);
    }

    public class MediaControlBinder extends Binder {
        public PlaybackControlService getService() {
            return PlaybackControlService.this;
        }
    }
}