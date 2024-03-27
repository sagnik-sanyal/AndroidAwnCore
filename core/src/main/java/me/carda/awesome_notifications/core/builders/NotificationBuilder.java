package me.carda.awesome_notifications.core.builders;

import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.text.HtmlCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import me.carda.awesome_notifications.core.AwesomeNotifications;
import me.carda.awesome_notifications.core.Definitions;
import me.carda.awesome_notifications.core.completion_handlers.NotificationThreadCompletionHandler;
import me.carda.awesome_notifications.core.enumerators.ActionType;
import me.carda.awesome_notifications.core.enumerators.GroupSort;
import me.carda.awesome_notifications.core.enumerators.NotificationCategory;
import me.carda.awesome_notifications.core.enumerators.NotificationImportance;
import me.carda.awesome_notifications.core.enumerators.NotificationLayout;
import me.carda.awesome_notifications.core.enumerators.NotificationLifeCycle;
import me.carda.awesome_notifications.core.enumerators.NotificationPermission;
import me.carda.awesome_notifications.core.enumerators.NotificationPlayState;
import me.carda.awesome_notifications.core.enumerators.NotificationPrivacy;
import me.carda.awesome_notifications.core.exceptions.AwesomeNotificationsException;
import me.carda.awesome_notifications.core.exceptions.ExceptionCode;
import me.carda.awesome_notifications.core.exceptions.ExceptionFactory;
import me.carda.awesome_notifications.core.logs.Logger;
import me.carda.awesome_notifications.core.managers.BadgeManager;
import me.carda.awesome_notifications.core.managers.ChannelManager;
import me.carda.awesome_notifications.core.managers.DefaultsManager;
import me.carda.awesome_notifications.core.managers.LocalizationManager;
import me.carda.awesome_notifications.core.managers.PermissionManager;
import me.carda.awesome_notifications.core.managers.StatusBarManager;
import me.carda.awesome_notifications.core.models.NotificationButtonModel;
import me.carda.awesome_notifications.core.models.NotificationChannelModel;
import me.carda.awesome_notifications.core.models.NotificationContentModel;
import me.carda.awesome_notifications.core.models.NotificationLocalizationModel;
import me.carda.awesome_notifications.core.models.NotificationMessageModel;
import me.carda.awesome_notifications.core.models.NotificationModel;
import me.carda.awesome_notifications.core.models.returnedData.ActionReceived;
import me.carda.awesome_notifications.core.threads.NotificationSender;
import me.carda.awesome_notifications.core.utils.BitmapUtils;
import me.carda.awesome_notifications.core.utils.BooleanUtils;
import me.carda.awesome_notifications.core.utils.HtmlUtils;
import me.carda.awesome_notifications.core.utils.IntegerUtils;
import me.carda.awesome_notifications.core.utils.ListUtils;
import me.carda.awesome_notifications.core.utils.StringUtils;


class DescendingComparator implements Comparator<String> {
    @Override
    public int compare(String s1, String s2) {
        return s2.compareTo(s1);
    }
}

public class NotificationBuilder {

    public static String TAG = "NotificationBuilder";

    private static String mainTargetClassName;

    private final BitmapUtils bitmapUtils;
    private final StringUtils stringUtils;
    private final PermissionManager permissionManager;
    private static MediaSessionCompat mediaSession;

    // *************** DEPENDENCY INJECTION CONSTRUCTOR ***************

    NotificationBuilder(
            StringUtils stringUtils,
            BitmapUtils bitmapUtils,
            PermissionManager permissionManager
    ) {
        this.stringUtils = stringUtils;
        this.bitmapUtils = bitmapUtils;
        this.permissionManager = permissionManager;
    }

    public static NotificationBuilder getNewBuilder() {
        return new NotificationBuilder(
                StringUtils.getInstance(),
                BitmapUtils.getInstance(),
                PermissionManager.getInstance());
    }

    // ****************************************************************

    public void forceBringAppToForeground(Context context) {
        Intent startActivity = new Intent(context, getMainTargetClass(context));
        startActivity.setFlags(
                // Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                        // Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
                        Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(startActivity);
    }

    public ActionReceived receiveNotificationActionFromIntent(
            Context context,
            Intent intent,
            NotificationLifeCycle appLifeCycle
    ) throws Exception {

        ActionReceived actionReceived
                = buildNotificationActionFromIntent(context, intent, appLifeCycle);

        if (actionReceived != null) {
            if (notificationActionShouldAutoDismiss(actionReceived))
                StatusBarManager
                        .getInstance(context)
                        .dismissNotification(context, actionReceived.id);

            if (actionReceived.actionType == ActionType.DisabledAction)
                return null;
        }

        return actionReceived;
    }

    public boolean notificationActionShouldAutoDismiss(ActionReceived actionReceived) {
        if (!StringUtils.getInstance().isNullOrEmpty(actionReceived.buttonKeyInput)) {
            return false;
        }
        return actionReceived.shouldAutoDismiss && actionReceived.autoDismissible;
    }

    @SuppressWarnings("unchecked")
    public Notification createNewAndroidNotification(Context context, Intent originalIntent, NotificationModel notificationModel) throws AwesomeNotificationsException {

        NotificationChannelModel channelModel =
                ChannelManager
                        .getInstance()
                        .getChannelByKey(context, notificationModel.content.channelKey);

        if (channelModel == null)
            throw ExceptionFactory
                    .getInstance()
                    .createNewAwesomeException(
                            TAG,
                            ExceptionCode.CODE_INVALID_ARGUMENTS,
                            "Channel '" + notificationModel.content.channelKey + "' does not exist",
                            ExceptionCode.DETAILED_INVALID_ARGUMENTS + ".channel.notFound." + notificationModel.content.channelKey);

        if (!ChannelManager
                .getInstance()
                .isChannelEnabled(context, notificationModel.content.channelKey))
            throw ExceptionFactory
                    .getInstance()
                    .createNewAwesomeException(
                            TAG,
                            ExceptionCode.CODE_INSUFFICIENT_PERMISSIONS,
                            "Channel '" + notificationModel.content.channelKey + "' is disabled",
                            ExceptionCode.DETAILED_INSUFFICIENT_PERMISSIONS + ".channel.disabled." + notificationModel.content.channelKey);

        NotificationCompat.Builder builder =
                getNotificationBuilderFromModel(
                        context,
                        originalIntent,
                        channelModel,
                        notificationModel);

        Notification androidNotification = builder.build();
        if (androidNotification.extras == null)
            androidNotification.extras = new Bundle();

        updateTrackingExtras(notificationModel, channelModel, androidNotification.extras);

        setWakeUpScreen(context, notificationModel);
        setCriticalAlert(context, channelModel);
        setCategoryFlags(context, notificationModel, androidNotification);

        setBadge(context, notificationModel, channelModel, builder);

        return androidNotification;
    }

    public Intent buildNotificationIntentFromNotificationModel(
            Context context,
            Intent originalIntent,
            String ActionReference,
            NotificationModel notificationModel,
            NotificationChannelModel channel,
            ActionType actionType,
            Class targetClass
    ) {
        Intent intent = new Intent(context, targetClass);

        // Preserves analytics extras
        if (originalIntent != null)
            intent.putExtras(originalIntent.getExtras());

        intent.setAction(ActionReference);

        if (actionType == ActionType.Default)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Bundle extras = intent.getExtras();
        if (extras == null)
            extras = new Bundle();

        String jsonData = notificationModel.toJson();
        extras.putString(Definitions.NOTIFICATION_JSON, jsonData);

        updateTrackingExtras(notificationModel, channel, extras);
        intent.putExtras(extras);

        return intent;
    }

    public Intent buildNotificationIntentFromActionModel(
            Context context,
            Intent originalIntent,
            String ActionReference,
            ActionReceived actionReceived,
            Class<?> targetAction
    ) {
        Intent intent = new Intent(context, targetAction);

        // Preserves analytics extras
        if (originalIntent != null)
            intent.putExtras(originalIntent.getExtras());

        intent.setAction(ActionReference);

        Bundle extras = intent.getExtras();
        if (extras == null)
            extras = new Bundle();

        String jsonData = actionReceived.toJson();
        extras.putString(Definitions.NOTIFICATION_ACTION_JSON, jsonData);
        extras.putBoolean(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED, actionReceived.isAuthenticationRequired);
        intent.putExtras(extras);

        return intent;
    }

    private PendingIntent getPendingActionIntent(
            Context context,
            Intent originalIntent,
            NotificationModel notificationModel,
            NotificationChannelModel channelModel
    ) {
        ActionType actionType = notificationModel.content.actionType;

        Intent actionIntent = buildNotificationIntentFromNotificationModel(
                context,
                originalIntent,
                Definitions.SELECT_NOTIFICATION,
                notificationModel,
                channelModel,
                actionType,
                notificationModel.content.category == NotificationCategory.Call ?
                        tryResolveClassName("CallActivity") :
                        actionType ==
                                ActionType.Default ?
                                getMainTargetClass(context) :
                                AwesomeNotifications.actionReceiverClass
        );

        if (actionType == ActionType.Default)
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        return actionType == ActionType.Default ?
                PendingIntent.getActivity(
                        context,
                        notificationModel.content.id,
                        actionIntent,
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ?
                                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                                PendingIntent.FLAG_UPDATE_CURRENT)
                :
                PendingIntent.getBroadcast(
                        context,
                        notificationModel.content.id,
                        actionIntent,
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ?
                                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getPendingDismissIntent(
            Context context,
            Intent originalIntent,
            NotificationModel notificationModel,
            NotificationChannelModel channelModel
    ) {
        Intent deleteIntent = buildNotificationIntentFromNotificationModel(
                context,
                originalIntent,
                Definitions.DISMISSED_NOTIFICATION,
                notificationModel,
                channelModel,
                notificationModel.content.actionType,
                AwesomeNotifications.dismissReceiverClass
        );

        return PendingIntent.getBroadcast(
                context,
                notificationModel.content.id,
                deleteIntent,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ?
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                        PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    @SuppressWarnings("unchecked")
    private void updateTrackingExtras(NotificationModel notificationModel, NotificationChannelModel channel, Bundle extras) {
        String groupKey = getGroupKey(notificationModel.content, channel);

        extras.putInt(Definitions.NOTIFICATION_ID, notificationModel.content.id);
        extras.putString(Definitions.NOTIFICATION_CHANNEL_KEY, stringUtils.digestString(notificationModel.content.channelKey));
        extras.putString(Definitions.NOTIFICATION_GROUP_KEY, stringUtils.digestString(groupKey));
        extras.putBoolean(Definitions.NOTIFICATION_AUTO_DISMISSIBLE, notificationModel.content.autoDismissible);
        extras.putBoolean(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED, false);
        extras.putString(Definitions.NOTIFICATION_ACTION_TYPE,
                notificationModel.content.actionType != null ?
                        notificationModel.content.actionType.toString() : ActionType.Default.toString());

        if (!ListUtils.isNullOrEmpty(notificationModel.content.messages)) {
            Map<String, Object> contentData = notificationModel.content.toMap();
            List<Map> contentMessageData = null;
            if (contentData.get(Definitions.NOTIFICATION_MESSAGES) instanceof List) {
                contentMessageData = (List<Map>) contentData.get(Definitions.NOTIFICATION_MESSAGES);
            }
            if (contentMessageData != null)
                extras.putSerializable(
                        Definitions.NOTIFICATION_MESSAGES,
                        (Serializable) contentMessageData);
        }
    }

    private Class tryResolveClassName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            ExceptionFactory
                    .getInstance()
                    .registerNewAwesomeException(
                            TAG,
                            ExceptionCode.CODE_CLASS_NOT_FOUND,
                            "Was not possible to resolve the class named '" + className + "'",
                            ExceptionCode.DETAILED_CLASS_NOT_FOUND + "." + className);
            return null;
        }
    }

    public Class getMainTargetClass(
            Context applicationContext
    ) {
        if (mainTargetClassName == null)
            updateMainTargetClassName(applicationContext);

        if (mainTargetClassName == null)
            mainTargetClassName = AwesomeNotifications.getPackageName(applicationContext) + ".MainActivity";

        Class clazz = tryResolveClassName(mainTargetClassName);
        if (clazz != null) return clazz;

        return tryResolveClassName("MainActivity");
    }

    public NotificationBuilder updateMainTargetClassName(Context applicationContext) {

        String packageName = AwesomeNotifications.getPackageName(applicationContext);
        Intent intent = new Intent();
        intent.setPackage(packageName);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfoList =
                applicationContext
                        .getPackageManager()
                        .queryIntentActivities(intent, 0);

        if (!resolveInfoList.isEmpty())
            mainTargetClassName = resolveInfoList.get(0).activityInfo.name;

        return this;
    }

    public NotificationBuilder setMediaSession(MediaSessionCompat mediaSession) {
        NotificationBuilder.mediaSession = mediaSession;
        return this;
    }

    public Intent getLaunchIntent(Context applicationContext) {
        String packageName = AwesomeNotifications.getPackageName(applicationContext);
        return applicationContext.getPackageManager().getLaunchIntentForPackage(packageName);
    }

    public ActionReceived buildNotificationActionFromIntent(Context context, Intent intent, NotificationLifeCycle lifeCycle) throws AwesomeNotificationsException {
        String buttonKeyPressed = intent.getAction();

        if (buttonKeyPressed == null) return null;

        boolean isNormalAction =
                Definitions.SELECT_NOTIFICATION.equals(buttonKeyPressed) ||
                        Definitions.DISMISSED_NOTIFICATION.equals(buttonKeyPressed);

        boolean isButtonAction =
                buttonKeyPressed
                        .startsWith(Definitions.NOTIFICATION_BUTTON_ACTION_PREFIX);

        if (isNormalAction || isButtonAction) {

            String notificationActionJson = intent.getStringExtra(Definitions.NOTIFICATION_ACTION_JSON);
            if (!stringUtils.isNullOrEmpty(notificationActionJson)) {
                ActionReceived actionModel = new ActionReceived().fromJson(notificationActionJson);
                if (actionModel != null) {
                    actionModel.isAuthenticationRequired = intent
                            .getBooleanExtra(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED, false);
                    return actionModel;
                }
            }

            String notificationJson = intent.getStringExtra(Definitions.NOTIFICATION_JSON);

            NotificationModel notificationModel = new NotificationModel().fromJson(notificationJson);
            if (notificationModel == null) return null;

            ActionReceived actionModel =
                    new ActionReceived(
                            notificationModel.content,
                            intent);

            actionModel.registerUserActionEvent(lifeCycle);

            if (actionModel.displayedDate == null)
                actionModel.registerDisplayedEvent(lifeCycle);

            actionModel.autoDismissible = intent.getBooleanExtra(Definitions.NOTIFICATION_AUTO_DISMISSIBLE, true);
            actionModel.isAuthenticationRequired = intent.getBooleanExtra(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED, false);
            actionModel.shouldAutoDismiss = actionModel.autoDismissible;

            actionModel.actionType =
                    stringUtils.getEnumFromString(
                            ActionType.class,
                            intent.getStringExtra(Definitions.NOTIFICATION_ACTION_TYPE));

            if (isButtonAction) {

                actionModel.buttonKeyPressed = intent.getStringExtra(Definitions.NOTIFICATION_BUTTON_KEY);

                Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
                if (remoteInput != null)
                    actionModel.buttonKeyInput = remoteInput.getCharSequence(
                            actionModel.buttonKeyPressed).toString();
                else
                    actionModel.buttonKeyInput = "";

                if (
                        !stringUtils.isNullOrEmpty(actionModel.buttonKeyInput)
                )
                    updateRemoteHistoryOnActiveNotification(
                            context,
                            notificationModel,
                            actionModel,
                            null);
            }

            return actionModel;
        }
        return null;
    }

    public void updateRemoteHistoryOnActiveNotification(
            Context context,
            NotificationModel notificationModel,
            ActionReceived actionModel,
            NotificationThreadCompletionHandler completionHandler
    ) throws AwesomeNotificationsException {
        if (
                !stringUtils.isNullOrEmpty(actionModel.buttonKeyInput) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N /*Android 7*/
        ) {
            actionModel.shouldAutoDismiss = false;

            switch (notificationModel.content.notificationLayout) {

                case Inbox:
                case BigText:
                case BigPicture:
                case ProgressBar:
                case MediaPlayer:
                case Default:
                    notificationModel.remoteHistory = actionModel.buttonKeyInput;
                    NotificationSender
                            .send(
                                    context,
                                    this,
                                    notificationModel.content.displayedLifeCycle,
                                    notificationModel,
                                    completionHandler);
                    break;
            }
        }
    }

    public String getAppName(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT_WATCH)
    public void wakeUpScreen(Context context) {

        String appName = getAppName(context);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        boolean isScreenOn = Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn(); // check if screen is on
        if (!isScreenOn) {
            PowerManager.WakeLock wl =
                    pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            appName + ":" + TAG + ":WakeupLock");

            wl.acquire(3000); //set your time in milliseconds
        }
        /*
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm.isInteractive();
        if(!isScreenOn)
        {
            String appName = getAppName(context);

            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP |
                    PowerManager.ON_AFTER_RELEASE,
                    appName+":"+TAG+":WakeupLock");
            wl.acquire(10000);

            PowerManager.WakeLock wl_cpu = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    appName+":"+TAG+":WakeupCpuLock");
            wl_cpu.acquire(10000);
            wl_cpu.acquire(10000);
        }*/
    }

    public void ensureCriticalAlert(Context context) throws AwesomeNotificationsException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (PermissionManager.getInstance().isDndOverrideAllowed(context)) {
                if (!permissionManager.isSpecifiedPermissionGloballyAllowed(context, NotificationPermission.CriticalAlert)) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P /*Android 9*/) {
                        NotificationManager.Policy policy = new NotificationManager.Policy(PRIORITY_CATEGORY_ALARMS, 0, 0);
                        notificationManager.setNotificationPolicy(policy);
                    }
                }
            }
        }
    }

    private NotificationCompat.Builder getNotificationBuilderFromModel(
            Context context,
            Intent originalIntent,
            NotificationChannelModel channel,
            NotificationModel notificationModel
    ) throws AwesomeNotificationsException {

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(
                        context,
                        notificationModel.content.channelKey);

        setChannelKey(context, channel, builder);
        setNotificationId(notificationModel);

        setCurrentTranslation(context, notificationModel);

        setTitle(notificationModel, builder);
        setBody(notificationModel, builder);
        setSummary(notificationModel, builder);

        setGroupKey(notificationModel, channel);
        setSmallIcon(context, notificationModel, channel, builder);
        setRemoteHistory(notificationModel, builder);
        setGrouping(context, notificationModel, channel, builder);
        setVisibility(context, notificationModel, channel, builder);
        setShowWhen(notificationModel, builder);
        setLayout(context, originalIntent, notificationModel, channel, builder);
        setAutoCancel(notificationModel, builder);
        setTicker(notificationModel, builder);
        setOnlyAlertOnce(notificationModel, channel, builder);
        setLockedNotification(notificationModel, channel, builder);
        setImportance(channel, builder);
        setCategory(notificationModel, builder);
        setChronometer(notificationModel, builder);
        setTimeoutAfter(notificationModel, builder);

        setSound(context, notificationModel, channel, builder);
        setVibrationPattern(channel, builder);
        setLights(channel, builder);

        setSmallIcon(context, notificationModel, channel, builder);
        setLargeIcon(context, notificationModel, builder);
        setLayoutColor(context, notificationModel, channel, builder);

        PendingIntent pendingActionIntent =
                getPendingActionIntent(context, originalIntent, notificationModel, channel);
        PendingIntent pendingDismissIntent =
                getPendingDismissIntent(context, originalIntent, notificationModel, channel);

        setFullScreenIntent(context, pendingActionIntent, notificationModel, builder);

        setNotificationPendingIntents(notificationModel, pendingActionIntent, pendingDismissIntent, builder);
        createActionButtons(context, originalIntent, notificationModel, channel, builder);

        return builder;
    }

    private void setChannelKey(Context context, NotificationChannelModel channel, NotificationCompat.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O /*Android 8*/) {
            NotificationChannel androidChannel =
                    ChannelManager
                            .getInstance()
                            .getAndroidChannel(context, channel.channelKey);

            builder.setChannelId(androidChannel.getId());
        }
    }

    private void setNotificationId(NotificationModel notificationModel) {
        if (notificationModel.content.id == null || notificationModel.content.id < 0)
            notificationModel.content.id = IntegerUtils.generateNextRandomId();
    }

    private void setGroupKey(NotificationModel notificationModel, NotificationChannelModel channel) {
        notificationModel.content.groupKey = getGroupKey(notificationModel.content, channel);
    }

    private void setCategoryFlags(Context context, NotificationModel notificationModel, Notification androidNotification) {

        if (notificationModel.content.category != null)
            switch (notificationModel.content.category) {

                case Alarm:
                    androidNotification.flags |= Notification.FLAG_INSISTENT;
                    androidNotification.flags |= Notification.FLAG_NO_CLEAR;
                    break;

                case Call:
                    androidNotification.flags |= Notification.FLAG_INSISTENT;
                    androidNotification.flags |= Notification.FLAG_HIGH_PRIORITY;
                    androidNotification.flags |= Notification.FLAG_NO_CLEAR;
                    break;
            }
    }

    private void setNotificationPendingIntents(NotificationModel notificationModel, PendingIntent pendingActionIntent, PendingIntent pendingDismissIntent, NotificationCompat.Builder builder) {
        builder.setContentIntent(pendingActionIntent);
        if (!notificationModel.groupSummary)
            builder.setDeleteIntent(pendingDismissIntent);
    }

    private void setWakeUpScreen(Context context, NotificationModel notificationModel) {
        if (notificationModel.content.wakeUpScreen)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH)
            wakeUpScreen(context);
    }

    private void setCriticalAlert(Context context, NotificationChannelModel channel) throws AwesomeNotificationsException {
        if (channel.criticalAlerts)
            ensureCriticalAlert(context);
    }

    private void setFullScreenIntent(Context context, PendingIntent pendingIntent, NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (BooleanUtils.getInstance().getValue(notificationModel.content.fullScreenIntent)) {
            builder.setFullScreenIntent(pendingIntent, true);
        }
    }

    private void setShowWhen(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        builder.setShowWhen(BooleanUtils.getInstance().getValueOrDefault(notificationModel.content.showWhen, true));
    }

    private Integer getBackgroundColor(NotificationModel notificationModel, NotificationChannelModel channel, NotificationCompat.Builder builder) {
        Integer bgColorValue;
        bgColorValue = IntegerUtils.extractInteger(notificationModel.content.backgroundColor, null);
        if (bgColorValue != null) {
            builder.setColorized(true);
        } else {
            bgColorValue = getLayoutColor(notificationModel, channel);
        }
        return bgColorValue;
    }

    private Integer getLayoutColor(NotificationModel notificationModel, NotificationChannelModel channel) {
        Integer layoutColorValue;
        layoutColorValue = IntegerUtils.extractInteger(notificationModel.content.color, channel.defaultColor);
        layoutColorValue = IntegerUtils.extractInteger(layoutColorValue, Color.BLACK);
        return layoutColorValue;
    }

    private void setImportance(NotificationChannelModel channel, NotificationCompat.Builder builder) {
        builder.setPriority(NotificationImportance.toAndroidPriority(channel.importance));
    }

    private void setCategory(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.category != null)
            builder.setCategory(notificationModel.content.category.rawValue);
    }

    private void setOnlyAlertOnce(NotificationModel notificationModel, NotificationChannelModel channel, NotificationCompat.Builder builder) {
        boolean onlyAlertOnceValue = BooleanUtils.getInstance().getValue(notificationModel.content.notificationLayout == NotificationLayout.ProgressBar || channel.onlyAlertOnce);
        builder.setOnlyAlertOnce(onlyAlertOnceValue);
    }

    private void setChronometer(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (
                notificationModel.content.chronometer == null ||
                        notificationModel.content.chronometer < 0 ||
                        !notificationModel.content.showWhen
        ) {
            return;
        }
        builder.setWhen(System.currentTimeMillis() - notificationModel.content.chronometer * 1000);
        builder.setUsesChronometer(true);
    }

    private void setTimeoutAfter(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.timeoutAfter == null) return;
        if (notificationModel.content.timeoutAfter < 1) return;
        builder.setTimeoutAfter(notificationModel.content.timeoutAfter * 1000);
    }

    private void setRemoteHistory(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (!stringUtils.isNullOrEmpty(notificationModel.remoteHistory) && notificationModel.content.notificationLayout == NotificationLayout.Default)
            builder.setRemoteInputHistory(new CharSequence[]{notificationModel.remoteHistory});
    }

    private void setLockedNotification(NotificationModel notificationModel, NotificationChannelModel channel, NotificationCompat.Builder builder) {
        boolean contentLocked = BooleanUtils.getInstance().getValue(notificationModel.content.locked);
        boolean channelLocked = BooleanUtils.getInstance().getValue(channel.locked);

        if (contentLocked) {
            builder.setOngoing(true);
        } else if (channelLocked) {
            boolean lockedValue = BooleanUtils.getInstance().getValueOrDefault(notificationModel.content.locked, true);
            builder.setOngoing(lockedValue);
        }
    }

    private void setTicker(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        String tickerValue;
        tickerValue = stringUtils.getValueOrDefault(notificationModel.content.ticker, "");
        tickerValue = stringUtils.getValueOrDefault(tickerValue, notificationModel.content.summary);
        tickerValue = stringUtils.getValueOrDefault(tickerValue, notificationModel.content.body);
        tickerValue = stringUtils.getValueOrDefault(tickerValue, notificationModel.content.title);
        builder.setTicker(tickerValue);
    }

    private void setBadge(Context context, NotificationModel notificationModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.badge != null) {
            BadgeManager.getInstance().setGlobalBadgeCounter(context, notificationModel.content.badge);
            return;
        }
        if (!notificationModel.groupSummary && BooleanUtils.getInstance().getValue(channelModel.channelShowBadge)) {
            BadgeManager.getInstance().incrementGlobalBadgeCounter(context);
            builder.setNumber(1);
        }
    }

    private void setAutoCancel(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        builder.setAutoCancel(BooleanUtils.getInstance().getValueOrDefault(notificationModel.content.autoDismissible, true));
    }

    private void setCurrentTranslation(
            Context context,
            NotificationModel notificationModel
    ) {
        String languageCode =
                LocalizationManager
                        .getInstance()
                        .getLocalization(context);

        Resources resources = getLocalizedResources(context, languageCode);

        if (notificationModel.content != null) {
            if (notificationModel.content.titleLocKey != null) {
                String titleLocKey = notificationModel.content.titleLocKey;
                try {
                    String localizedTitle = resources.getString(
                            context.getResources().getIdentifier(
                                    titleLocKey,
                                    "string",
                                    context.getPackageName()
                            )
                    );

                    if (!StringUtils.getInstance().isNullOrEmpty(localizedTitle)) {
                        localizedTitle = localizedTitle.replaceAll("(?<!\\\\\\\\)%@", "%s");
                        if (notificationModel.content.titleLocArgs != null) {
                            for (String arg : notificationModel.content.titleLocArgs) {
                                localizedTitle = String.format(localizedTitle, arg);
                            }
                        }
                        notificationModel.content.title = localizedTitle;
                    }
                } catch (Exception e) {
                    ExceptionFactory
                            .getInstance()
                            .registerNewAwesomeException(
                                    TAG,
                                    ExceptionCode.CODE_INVALID_ARGUMENTS,
                                    "The key or args requested are invalid for title translation",
                                    ExceptionCode.DETAILED_INVALID_ARGUMENTS,
                                    e);
                }
            }

            if (notificationModel.content.bodyLocKey != null) {
                String bodyLocKey = notificationModel.content.bodyLocKey;
                try {
                    String localizedBody = resources.getString(
                            context.getResources().getIdentifier(
                                    bodyLocKey,
                                    "string",
                                    context.getPackageName()
                            )
                    );

                    if (!StringUtils.getInstance().isNullOrEmpty(localizedBody)) {
                        localizedBody = localizedBody.replaceAll("(?<!\\\\\\\\)%@", "%s");
                        if (notificationModel.content.bodyLocArgs != null) {
                            for (String arg : notificationModel.content.bodyLocArgs) {
                                localizedBody = String.format(localizedBody, arg);
                            }
                        }
                        notificationModel.content.body = localizedBody;
                    }
                } catch (Exception e) {
                    ExceptionFactory
                            .getInstance()
                            .registerNewAwesomeException(
                                    TAG,
                                    ExceptionCode.CODE_INVALID_ARGUMENTS,
                                    "The key or args requested are invalid for body translation",
                                    ExceptionCode.DETAILED_INVALID_ARGUMENTS,
                                    e);
                }
            }
        }

        if (notificationModel.localizations == null) return;
        if (notificationModel.localizations.isEmpty()) return;

        String matchedTranslationCode = getMatchedLanguageCode(
                notificationModel.localizations,
                languageCode);
        if (matchedTranslationCode == null) return;

        NotificationLocalizationModel localizationModel = notificationModel
                .localizations
                .get(matchedTranslationCode);
        if (localizationModel == null) return;

        if (!StringUtils.getInstance().isNullOrEmpty(localizationModel.title)) {
            notificationModel.content.title = localizationModel.title;
        }
        if (!StringUtils.getInstance().isNullOrEmpty(localizationModel.body)) {
            notificationModel.content.body = localizationModel.body;
        }
        if (!StringUtils.getInstance().isNullOrEmpty(localizationModel.summary)) {
            notificationModel.content.summary = localizationModel.summary;
        }
        if (!StringUtils.getInstance().isNullOrEmpty(localizationModel.largeIcon)) {
            notificationModel.content.largeIcon = localizationModel.largeIcon;
        }
        if (!StringUtils.getInstance().isNullOrEmpty(localizationModel.bigPicture)) {
            notificationModel.content.bigPicture = localizationModel.bigPicture;
        }

        if (localizationModel.buttonLabels == null) return;
        if (notificationModel.actionButtons == null) return;

        for (NotificationButtonModel buttonModel : notificationModel.actionButtons) {
            if (localizationModel.buttonLabels.containsKey(buttonModel.key)) {
                buttonModel.label = localizationModel.buttonLabels.get(buttonModel.key);
            }
        }
    }

    private Resources getLocalizedResources(Context context, String languageCode) {
        String[] parts = languageCode.split("-");
        String language = parts[0].toLowerCase();
        String country = parts.length > 1 ? parts[1].toUpperCase() : "";

        Locale locale = country.isEmpty() ? new Locale(language) : new Locale(language, country);

        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);

        Context localizedContext = context.createConfigurationContext(config);
        return localizedContext.getResources();
    }

    private String getMatchedLanguageCode(Map<String, NotificationLocalizationModel> localizations, String languageCode) {
        String lowercaseLanguageCode = languageCode.toLowerCase(Locale.ROOT);
        if (localizations.containsKey(lowercaseLanguageCode)) return lowercaseLanguageCode;

        lowercaseLanguageCode = lowercaseLanguageCode
                .replace("-", "_");

        Set<String> sortedSet = new TreeSet<>(new DescendingComparator());
        sortedSet.addAll(localizations.keySet());
        String exactWord = null, keyStartedWith = null, codeStartedWith = null;
        for (String key : sortedSet) {
            String lowercaseKey = key
                    .toLowerCase(Locale.ROOT)
                    .replace("-", "_");

            if (lowercaseKey.equals(lowercaseLanguageCode)) {
                exactWord = key;
                continue;
            }
            if (lowercaseKey.startsWith(lowercaseLanguageCode + "_")) {
                keyStartedWith = key;
                continue;
            }
            if (lowercaseLanguageCode.startsWith(lowercaseKey + "_")) {
                codeStartedWith = key;
            }
        }

        if (!StringUtils.getInstance().isNullOrEmpty(exactWord)) return exactWord;
        if (!StringUtils.getInstance().isNullOrEmpty(keyStartedWith)) return keyStartedWith;
        if (!StringUtils.getInstance().isNullOrEmpty(codeStartedWith)) return codeStartedWith;

        return null;
    }

    private void setTitle(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.title == null) return;
        builder.setContentTitle(HtmlUtils.fromHtml(notificationModel.content.title));
    }

    private void setBody(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.body == null) return;
        builder.setContentText(HtmlUtils.fromHtml(notificationModel.content.body));
    }

    private void setSummary(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.summary == null) return;
        builder.setSubText(HtmlUtils.fromHtml(notificationModel.content.summary));
    }

    private void setVibrationPattern(NotificationChannelModel channelModel, NotificationCompat.Builder builder) {
        if (BooleanUtils.getInstance().getValue(channelModel.enableVibration)) {
            if (channelModel.vibrationPattern != null && channelModel.vibrationPattern.length > 0) {
                builder.setVibrate(channelModel.vibrationPattern);
            }
        } else {
            builder.setVibrate(new long[]{0});
        }
    }

    private void setLights(NotificationChannelModel channelModel, NotificationCompat.Builder builder) {
        if (BooleanUtils.getInstance().getValue(channelModel.enableLights)) {
            Integer ledColorValue = IntegerUtils.extractInteger(channelModel.ledColor, Color.WHITE);
            Integer ledOnMsValue = IntegerUtils.extractInteger(channelModel.ledOnMs, 300);
            Integer ledOffMsValue = IntegerUtils.extractInteger(channelModel.ledOffMs, 700);
            builder.setLights(ledColorValue, ledOnMsValue, ledOffMsValue);
        }
    }

    private void setVisibility(Context context, NotificationModel notificationModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) {

//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        NotificationPrivacy privacy =
                notificationModel.content.privacy != null ?
                        notificationModel.content.privacy :
                        channelModel.defaultPrivacy;

        builder.setVisibility(NotificationPrivacy.toAndroidPrivacy(privacy));
//        }
    }

    private void setLayoutColor(Context context, NotificationModel notificationModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) {

        if (notificationModel.content.backgroundColor == null) {
            builder.setColor(getLayoutColor(notificationModel, channelModel));
        } else {
            builder.setColor(getBackgroundColor(notificationModel, channelModel, builder));
        }
    }

    private void setLargeIcon(Context context, NotificationModel notificationModel, NotificationCompat.Builder builder) {
        if (notificationModel.content.notificationLayout == NotificationLayout.BigPicture) return;

        String largeIconReference = notificationModel.content.largeIcon;
        if (!stringUtils.isNullOrEmpty(largeIconReference)) {
            Bitmap largeIcon = bitmapUtils.getBitmapFromSource(
                    context,
                    largeIconReference,
                    notificationModel.content.roundedLargeIcon);

            if (largeIcon != null)
                builder.setLargeIcon(largeIcon);
        }
    }

    @SuppressLint("WrongConstant")
    @NonNull
    public void createActionButtons(
            Context context,
            Intent originalIntent,
            NotificationModel notificationModel,
            NotificationChannelModel channel,
            NotificationCompat.Builder builder
    ) {
        if (ListUtils.isNullOrEmpty(notificationModel.actionButtons)) return;

        for (NotificationButtonModel buttonProperties : notificationModel.actionButtons) {

            // If reply is not available, do not show it
            if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.N /*Android 7*/ &&
                            buttonProperties.requireInputText
            ) continue;
            if (buttonProperties.label == null) continue;

            ActionType actionType = buttonProperties.actionType;
            String buttonLabel = buttonProperties.label;

            Intent actionIntent = buildNotificationIntentFromNotificationModel(
                    context,
                    originalIntent,
                    Definitions.NOTIFICATION_BUTTON_ACTION_PREFIX + "_" + buttonProperties.key,
                    notificationModel,
                    channel,
                    buttonProperties.actionType,
                    actionType == ActionType.Default ?
                            getMainTargetClass(context) :
                            AwesomeNotifications.actionReceiverClass
            );

            if (buttonProperties.actionType == ActionType.Default)
                actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            actionIntent.putExtra(Definitions.NOTIFICATION_AUTO_DISMISSIBLE, buttonProperties.autoDismissible);
            actionIntent.putExtra(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED, buttonProperties.isAuthenticationRequired);
            actionIntent.putExtra(Definitions.NOTIFICATION_SHOW_IN_COMPACT_VIEW, buttonProperties.showInCompactView);
            actionIntent.putExtra(Definitions.NOTIFICATION_ENABLED, buttonProperties.enabled);
            actionIntent.putExtra(Definitions.NOTIFICATION_BUTTON_KEY, buttonProperties.key);
            actionIntent.putExtra(Definitions.NOTIFICATION_ACTION_TYPE,
                    buttonProperties.actionType != null ?
                            buttonProperties.actionType.toString() : ActionType.Default.toString());

            PendingIntent actionPendingIntent = null;
            if (buttonProperties.enabled) {

                actionPendingIntent =
                        actionType == ActionType.Default ?
                                PendingIntent.getActivity(
                                        context,
                                        notificationModel.content.id,
                                        actionIntent,
                                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
                                                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                                                PendingIntent.FLAG_UPDATE_CURRENT)
                                :
                                PendingIntent.getBroadcast(
                                        context,
                                        notificationModel.content.id,
                                        actionIntent,
                                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
                                                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                                                PendingIntent.FLAG_UPDATE_CURRENT);
            }

            int iconResource = 0;
            if (!stringUtils.isNullOrEmpty(buttonProperties.icon)) {
                iconResource = bitmapUtils.getDrawableResourceId(context, buttonProperties.icon);
            }

            Spanned htmlLabel =
                    HtmlCompat.fromHtml(
                            buttonProperties.isDangerousOption ?
                                    "<font color=\"16711680\">" + buttonLabel + "</font>" :
                                    (
                                            buttonProperties.color != null ?
                                                    "<font color=\"" + buttonProperties.color.toString() + "\">" + buttonLabel + "</font>" :
                                                    buttonLabel
                                    ),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                    );

            boolean isAuthenticationRequired =
                    buttonProperties.isAuthenticationRequired != null &&
                            buttonProperties.isAuthenticationRequired;

            if (buttonProperties.requireInputText != null && buttonProperties.requireInputText) {

                RemoteInput remoteInput =
                        new RemoteInput
                                .Builder(buttonProperties.key)
                                .setLabel(buttonLabel)
                                .build();

                NotificationCompat.Action replyAction =
                        new NotificationCompat.Action
                                .Builder(
                                iconResource,
                                htmlLabel,
                                actionPendingIntent)
                                .setAuthenticationRequired(isAuthenticationRequired)
                                .addRemoteInput(remoteInput)
                                .build();

                builder.addAction(replyAction);

            } else {

                NotificationCompat.Action normalAction =
                        new NotificationCompat.Action
                                .Builder(
                                iconResource,
                                htmlLabel,
                                actionPendingIntent
                        )
                                .setAuthenticationRequired(isAuthenticationRequired)
                                .build();

                builder.addAction(normalAction);
            }
        }
    }

    private void setSound(Context context, NotificationModel notificationModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) {

        Uri uri = null;

        if (
                !notificationModel.content.isRefreshNotification &&
                        notificationModel.remoteHistory == null &&
                        BooleanUtils.getInstance().getValue(channelModel.playSound)
        ) {
            String soundSource = stringUtils.isNullOrEmpty(notificationModel.content.customSound) ? channelModel.soundSource : notificationModel.content.customSound;
            uri = ChannelManager
                    .getInstance()
                    .retrieveSoundResourceUri(context, channelModel.defaultRingtoneType, soundSource);
        }

        builder.setSound(uri);
    }

    private void setSmallIcon(Context context, NotificationModel notificationModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) throws AwesomeNotificationsException {
        if (!stringUtils.isNullOrEmpty(notificationModel.content.icon)) {
            builder.setSmallIcon(bitmapUtils.getDrawableResourceId(context, notificationModel.content.icon));
        } else if (!stringUtils.isNullOrEmpty(channelModel.icon)) {
            builder.setSmallIcon(bitmapUtils.getDrawableResourceId(context, channelModel.icon));
        } else {
            String defaultIcon = DefaultsManager
                    .getInstance(context)
                    .getDefaultIcon(context);

            if (stringUtils.isNullOrEmpty(defaultIcon)) {

                // for backwards compatibility: this is for handling the old way references to the icon used to be kept but should be removed in future
                if (channelModel.iconResourceId != null) {
                    builder.setSmallIcon(channelModel.iconResourceId);
                } else {
                    try {
                        int defaultResource = context.getResources().getIdentifier(
                                "ic_launcher",
                                "mipmap",
                                AwesomeNotifications.getPackageName(context)
                        );

                        if (defaultResource > 0) {
                            builder.setSmallIcon(defaultResource);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                int resourceIndex = bitmapUtils.getDrawableResourceId(context, defaultIcon);
                if (resourceIndex > 0) {
                    builder.setSmallIcon(resourceIndex);
                }
            }
        }
    }

    private void setGrouping(Context context, NotificationModel notificationModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) {

        if ( // Grouping key is reserved to arrange messaging and messaging group layouts
                notificationModel.content.notificationLayout == NotificationLayout.Messaging ||
                        notificationModel.content.notificationLayout == NotificationLayout.MessagingGroup
        ) return;

        String groupKey = getGroupKey(notificationModel.content, channelModel);

        if (!stringUtils.isNullOrEmpty(groupKey)) {
            builder.setGroup(groupKey);

            if (notificationModel.groupSummary)
                builder.setGroupSummary(true);

            String idText = notificationModel.content.id.toString();
            String sortKey = Long.toString(
                    (channelModel.groupSort == GroupSort.Asc ? System.currentTimeMillis() : Long.MAX_VALUE - System.currentTimeMillis())
            );

            builder.setSortKey(sortKey + idText);

            builder.setGroupAlertBehavior(channelModel.groupAlertBehavior.ordinal());
        }
    }

    private void setLayout(
            Context context,
            Intent originalIntent,
            NotificationModel notificationModel,
            NotificationChannelModel channelModel,
            NotificationCompat.Builder builder
    ) throws AwesomeNotificationsException {
        try {
            switch (notificationModel.content.notificationLayout) {

                case BigPicture:
                    if (setBigPictureLayout(context, notificationModel, builder)) return;
                    break;

                case BigText:
                    if (setBigTextStyle(context, notificationModel.content, builder)) return;
                    break;

                case Inbox:
                    if (setInboxLayout(context, notificationModel.content, builder)) return;
                    break;

                case Messaging:
                    if (setMessagingLayout(context, false, notificationModel.content, channelModel, builder))
                        return;
                    break;

                case MessagingGroup:
                    if (setMessagingLayout(context, true, notificationModel.content, channelModel, builder))
                        return;
                    break;

                case MediaPlayer:
                    if (setMediaPlayerLayout(context, notificationModel, builder, originalIntent, channelModel))
                        return;
                    break;

                case ProgressBar:
                    setProgressLayout(notificationModel, builder);
                    break;

                case Default:
                default:
                    break;
            }
        } catch (Exception e) {
            Logger.e(TAG, e.getMessage());
        }
    }

    private Boolean setBigPictureLayout(Context context, NotificationModel notificationModel, NotificationCompat.Builder builder) {
        NotificationContentModel contentModel = notificationModel.content;
        String bigPictureReference = contentModel.bigPicture;
        String largeIconReference = contentModel.largeIcon;

        Bitmap bigPicture = null, largeIcon = null;

        if (!stringUtils.isNullOrEmpty(bigPictureReference))
            bigPicture = bitmapUtils.getBitmapFromSource(context, bigPictureReference, contentModel.roundedBigPicture);

        if (contentModel.hideLargeIconOnExpand)
            largeIcon = bigPicture != null ?
                    bigPicture : (!stringUtils.isNullOrEmpty(largeIconReference) ?
                    bitmapUtils.getBitmapFromSource(
                            context,
                            largeIconReference,
                            contentModel.roundedLargeIcon || contentModel.roundedBigPicture) : null);
        else {
            boolean areEqual =
                    !stringUtils.isNullOrEmpty(largeIconReference) &&
                            largeIconReference.equals(bigPictureReference);

            if (areEqual)
                largeIcon = bigPicture;
            else if (!stringUtils.isNullOrEmpty(largeIconReference))
                largeIcon =
                        bitmapUtils.getBitmapFromSource(context, largeIconReference, contentModel.roundedLargeIcon);
        }

        if (largeIcon != null)
            builder.setLargeIcon(largeIcon);

        if (bigPicture == null)
            return false;

        NotificationCompat.BigPictureStyle bigPictureStyle = new NotificationCompat.BigPictureStyle();

        bigPictureStyle.bigPicture(bigPicture);
        bigPictureStyle.bigLargeIcon(contentModel.hideLargeIconOnExpand ? null : largeIcon);

        if (!stringUtils.isNullOrEmpty(contentModel.title)) {
            CharSequence contentTitle = HtmlUtils.fromHtml(contentModel.title);
            bigPictureStyle.setBigContentTitle(contentTitle);
        }

        if (!stringUtils.isNullOrEmpty(contentModel.body)) {
            CharSequence summaryText = HtmlUtils.fromHtml(contentModel.body);
            bigPictureStyle.setSummaryText(summaryText);
        }

        builder.setStyle(bigPictureStyle);

        return true;
    }

    private Boolean setBigTextStyle(Context context, NotificationContentModel contentModel, NotificationCompat.Builder builder) {

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();

        if (stringUtils.isNullOrEmpty(contentModel.body)) return false;

        CharSequence bigBody = HtmlUtils.fromHtml(contentModel.body);
        bigTextStyle.bigText(bigBody);

        if (!stringUtils.isNullOrEmpty(contentModel.summary)) {
            CharSequence bigSummary = HtmlUtils.fromHtml(contentModel.summary);
            bigTextStyle.setSummaryText(bigSummary);
        }

        if (!stringUtils.isNullOrEmpty(contentModel.title)) {
            CharSequence bigTitle = HtmlUtils.fromHtml(contentModel.title);
            bigTextStyle.setBigContentTitle(bigTitle);
        }

        builder.setStyle(bigTextStyle);

        return true;
    }

    private Boolean setInboxLayout(Context context, NotificationContentModel contentModel, NotificationCompat.Builder builder) {

        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

        if (stringUtils.isNullOrEmpty(contentModel.body)) return false;

        List<String> lines = new ArrayList<>(Arrays.asList(contentModel.body.split("\\r?\\n")));

        if (ListUtils.isNullOrEmpty(lines)) return false;

        CharSequence summary;
        if (stringUtils.isNullOrEmpty(contentModel.summary)) {
            summary = "+ " + lines.size() + " more";
        } else {
            summary = HtmlUtils.fromHtml(contentModel.body);
        }
        inboxStyle.setSummaryText(summary);

        if (!stringUtils.isNullOrEmpty(contentModel.title)) {
            CharSequence contentTitle = HtmlUtils.fromHtml(contentModel.title);
            inboxStyle.setBigContentTitle(contentTitle);
        }

        if (contentModel.summary != null) {
            CharSequence summaryText = HtmlUtils.fromHtml(contentModel.summary);
            inboxStyle.setSummaryText(summaryText);
        }

        for (String line : lines) {
            inboxStyle.addLine(HtmlUtils.fromHtml(line));
        }

        builder.setStyle(inboxStyle);
        return true;
    }

    public String getGroupKey(NotificationContentModel contentModel, NotificationChannelModel channelModel) {
        return !stringUtils.isNullOrEmpty(contentModel.groupKey) ?
                contentModel.groupKey : channelModel.groupKey;
    }

    public static final ConcurrentHashMap<String, List<NotificationMessageModel>> messagingQueue = new ConcurrentHashMap<String, List<NotificationMessageModel>>();

    @SuppressWarnings("unchecked")
    private Boolean setMessagingLayout(Context context, boolean isGrouping, NotificationContentModel contentModel, NotificationChannelModel channelModel, NotificationCompat.Builder builder) throws AwesomeNotificationsException {
        @NonNull final String username = contentModel.title;
        @Nullable final String groupName = contentModel.summary;
        if (StringUtils.getInstance().isNullOrEmpty(username)) return false;
        String groupKey = getGroupKey(contentModel, channelModel);

        //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M /*Android 6*/) {

        String messageQueueKey = groupKey + (isGrouping ? ".Gr" : "");

        int firstNotificationId = contentModel.id;
        List<String> groupIDs = StatusBarManager
                .getInstance(context)
                .activeNotificationsGroup.get(groupKey);

        if (groupIDs == null || groupIDs.isEmpty())
            messagingQueue.remove(messageQueueKey);
        else
            firstNotificationId = Integer.parseInt(groupIDs.get(0));

        NotificationMessageModel currentMessage = new NotificationMessageModel(
                username,
                groupName,
                contentModel.body,
                contentModel.largeIcon
        );

        List<NotificationMessageModel> messages = contentModel.messages;
        if (ListUtils.isNullOrEmpty(messages)) {
            messages = messagingQueue.get(messageQueueKey);
            if (messages == null)
                messages = new ArrayList<>();
        }

        messages.add(currentMessage);
        messagingQueue.put(messageQueueKey, messages);

        contentModel.id = firstNotificationId;
        contentModel.messages = messages;

        NotificationCompat.MessagingStyle messagingStyle =
                new NotificationCompat.MessagingStyle(username);

        for (NotificationMessageModel message : contentModel.messages) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P /*Android 9*/) {

                Person.Builder personBuilder = new Person.Builder()
                        .setName(username);

                String personIcon = message.largeIcon != null ? message.largeIcon : contentModel.largeIcon;
                if (!stringUtils.isNullOrEmpty(personIcon)) {
                    Bitmap largeIcon = bitmapUtils.getBitmapFromSource(
                            context,
                            personIcon,
                            contentModel.roundedLargeIcon);
                    if (largeIcon != null)
                        personBuilder.setIcon(
                                IconCompat.createWithBitmap(largeIcon));
                }

                Person person = personBuilder.build();

                messagingStyle.addMessage(
                        message.message, message.timestamp, person);
            } else {
                messagingStyle.addMessage(
                        message.message, message.timestamp, message.title);
            }
        }

        if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P /*Android 9*/ &&
                        contentModel.notificationLayout == NotificationLayout.MessagingGroup
        ) {
            messagingStyle.setConversationTitle(groupName);
            messagingStyle.setGroupConversation(isGrouping);
        }

        builder.setStyle((NotificationCompat.Style) messagingStyle);

        return true;
    }

    private Boolean setMediaPlayerLayout(
            Context context,
            NotificationModel notificationModel,
            NotificationCompat.Builder builder,
            Intent originalIntent,
            NotificationChannelModel channel
    ) throws AwesomeNotificationsException {
        NotificationContentModel contentModel = notificationModel.content;
        if (contentModel == null) return false;

        List<NotificationButtonModel> actionButtons = notificationModel.actionButtons == null
                ? new ArrayList<>() : notificationModel.actionButtons;

        ArrayList<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < actionButtons.size(); i++) {
            NotificationButtonModel b = actionButtons.get(i);
            if (b.showInCompactView != null && b.showInCompactView) indexes.add(i);
        }

        if (!StatusBarManager
                .getInstance(context)
                .isFirstActiveOnGroupKey(contentModel.groupKey)
        ) {
            List<String> lastIds = StatusBarManager.getInstance(context).activeNotificationsGroup.get(contentModel.groupKey);
            if (lastIds != null && lastIds.size() > 0)
                contentModel.id = Integer.parseInt(lastIds.get(0));
        }

        int[] showInCompactView = toIntArray(indexes);

        /*
         * This fix is to show the notification in Android versions >= 11 in the QuickSettings area.
         * https://developer.android.com/guide/topics/media/media-controls
         * https://github.com/rafaelsetragni/awesome_notifications/pull/364
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q /* Android 10 */) {

            if (mediaSession == null)
                throw ExceptionFactory
                        .getInstance()
                        .createNewAwesomeException(
                                TAG,
                                ExceptionCode.CODE_INITIALIZATION_EXCEPTION,
                                "There is no valid media session available",
                                ExceptionCode.DETAILED_INSUFFICIENT_REQUIREMENTS);

            MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
            if (contentModel.title != null) {
                metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, contentModel.title);
            }
            if (contentModel.body != null) {
                metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, contentModel.body);
            }
            if (contentModel.duration != null) {
                metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, contentModel.duration * 1000);
            }
            mediaSession.setMetadata(metadataBuilder.build());

            // using PlaybackState to update position
            if (contentModel.progress == null) contentModel.progress = 0f;
            if (contentModel.playState == null)
                contentModel.playState = NotificationPlayState.playing;
            if (contentModel.playbackSpeed == null) contentModel.playbackSpeed = 0f;
            if (contentModel.duration == null) contentModel.duration = 0;

            PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder()
                    .setState(
                            contentModel.playState.rawValue,
                            (long) (contentModel.progress * contentModel.duration * 10),
                            contentModel.playbackSpeed,
                            SystemClock.elapsedRealtime()
                    );

            /*
             * add action buttons in Android versions >= 11
             * https://developer.android.com/media/implement/surfaces/mobile#adding-custom-actions
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R /* Android 11 */) {
                for (int i = 0; i < actionButtons.size(); i++) {
                    NotificationButtonModel b = actionButtons.get(i);
                    int iconResource = 0;
                    if (!stringUtils.isNullOrEmpty(b.icon)) {
                        iconResource = bitmapUtils.getDrawableResourceId(context, b.icon);
                    }
                    PlaybackStateCompat.CustomAction.Builder actionBuilder = new PlaybackStateCompat.CustomAction.Builder(
                            b.key, // action ID
                            b.label, // title - used as content description for the button
                            iconResource // icon
                    );
                    // put button properties into extras
                    Bundle extras = new Bundle();
                    extras.putBoolean(Definitions.NOTIFICATION_ENABLED, b.enabled);
                    extras.putBoolean(Definitions.NOTIFICATION_AUTO_DISMISSIBLE, b.autoDismissible);
                    extras.putBoolean(Definitions.NOTIFICATION_SHOW_IN_COMPACT_VIEW, b.showInCompactView);
                    extras.putString(Definitions.NOTIFICATION_ACTION_TYPE, b.actionType.getSafeName());
                    actionBuilder.setExtras(extras);
                    playbackStateBuilder.addCustomAction(actionBuilder.build());
                }
                // add callback for custom action
                MediaSessionCompat.Callback callback = new MediaSessionCompat.Callback() {
                    @Override
                    public void onCustomAction(String action, Bundle extras) {
                        super.onCustomAction(action, extras);
                        boolean enabled = extras.getBoolean(Definitions.NOTIFICATION_ENABLED);
                        boolean autoDismissible = extras.getBoolean(Definitions.NOTIFICATION_AUTO_DISMISSIBLE);
                        boolean isAuthenticationRequired = extras.getBoolean(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED);
                        boolean showInCompactView = extras.getBoolean(Definitions.NOTIFICATION_SHOW_IN_COMPACT_VIEW);
                        ActionType actionType = ActionType.getSafeEnum(extras.getString(Definitions.NOTIFICATION_ACTION_TYPE));
                        Intent actionIntent = buildNotificationIntentFromNotificationModel(
                                context,
                                originalIntent,
                                Definitions.NOTIFICATION_BUTTON_ACTION_PREFIX + "_" + action,
                                notificationModel,
                                channel,
                                actionType,
                                actionType == ActionType.Default ?
                                        getMainTargetClass(context) :
                                        AwesomeNotifications.actionReceiverClass
                        );

                        if (actionType == ActionType.Default)
                            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        actionIntent.putExtra(Definitions.NOTIFICATION_AUTO_DISMISSIBLE, autoDismissible);
                        actionIntent.putExtra(Definitions.NOTIFICATION_AUTHENTICATION_REQUIRED, isAuthenticationRequired);
                        actionIntent.putExtra(Definitions.NOTIFICATION_SHOW_IN_COMPACT_VIEW, showInCompactView);
                        actionIntent.putExtra(Definitions.NOTIFICATION_ENABLED, enabled);
                        actionIntent.putExtra(Definitions.NOTIFICATION_BUTTON_KEY, action); // we use button's key as action, so action is the key
                        actionIntent.putExtra(
                                Definitions.NOTIFICATION_ACTION_TYPE,
                                actionType == null
                                        ? ActionType.Default.getSafeName()
                                        : actionType.getSafeName());

                        if (actionType != null && enabled) {
                            if (actionType == ActionType.Default) {
                                context.startActivity(actionIntent);
                            } else {
                                context.sendBroadcast(actionIntent);

                            }
                        }
                    }
                };
                mediaSession.setCallback(callback);
            }

            mediaSession.setPlaybackState(playbackStateBuilder.build());
        }

        builder.setStyle(
                new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(showInCompactView)
                        .setShowCancelButton(true));

        if (!stringUtils.isNullOrEmpty(contentModel.summary))
            builder.setSubText(contentModel.summary);

        if (contentModel.progress != null && IntegerUtils.isBetween(contentModel.progress.intValue(), 0, 100))
            builder.setProgress(
                    100,
                    Math.max(0, Math.min(100, IntegerUtils.extractInteger(contentModel.progress, 0))),
                    contentModel.progress == null);

        builder.setShowWhen(false);

        return true;
    }

    private void setProgressLayout(NotificationModel notificationModel, NotificationCompat.Builder builder) {
        builder.setProgress(
                100,
                Math.max(0, Math.min(100, IntegerUtils.extractInteger(notificationModel.content.progress, 0))),
                notificationModel.content.progress == null
        );
    }

    private int[] toIntArray(ArrayList<Integer> list) {
        if (list == null || list.size() <= 0) return new int[0];

        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }

        return result;
    }
}
