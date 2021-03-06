package com.baseflow.permissionhandler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.flutter.plugin.common.PluginRegistry;

final class PermissionManager {
    @FunctionalInterface
    interface ActivityRegistry {
        void addListener(PluginRegistry.ActivityResultListener handler);
    }

    @FunctionalInterface
    interface PermissionRegistry {
        void addListener(PluginRegistry.RequestPermissionsResultListener handler);
    }

    @FunctionalInterface
    interface RequestPermissionsSuccessCallback {
        void onSuccess(Map<Integer, Integer> results);
    }

    @FunctionalInterface
    interface CheckPermissionsSuccessCallback {
        void onSuccess(@PermissionConstants.PermissionStatus int permissionStatus);
    }

    @FunctionalInterface
    interface ShouldShowRequestPermissionRationaleSuccessCallback {
        void onSuccess(boolean shouldShowRequestPermissionRationale);
    }

    private boolean ongoing = false;
    private RequestPermissionsSuccessCallback successCallback;

    public RequestPermissionsSuccessCallback getSuccessCallback() {
        return successCallback;
    }
    private Map<Integer, Integer> successResult;
    private int sendingIntentCount = 0;
    public Map<Integer, Integer> getResult() {
        return successResult;
    }

    public void setSendingIntentCount(int sendingIntentCount) {
        this.sendingIntentCount = sendingIntentCount;
    }

    public int getSendingIntentCount() {
        return sendingIntentCount;
    }

    void checkPermissionStatus(
            @PermissionConstants.PermissionGroup int permission,
            Context context,
            Activity activity,
            CheckPermissionsSuccessCallback successCallback,
            ErrorCallback errorCallback) {

        successCallback.onSuccess(determinePermissionStatus(
                permission,
                context,
                activity));
    }

    void requestPermissions(
            List<Integer> permissions,
            Activity activity,
            ActivityRegistry activityRegistry,
            PermissionRegistry permissionRegistry,
            RequestPermissionsSuccessCallback successCallback,
            ErrorCallback errorCallback) {
        this.successCallback = successCallback;
        if (ongoing) {
            errorCallback.onError(
                    "PermissionHandler.PermissionManager",
                    "A request for permissions is already running, please wait for it to finish before doing another request (note that you can request multiple permissions at the same time).");
            return;
        }

        if (activity == null) {
            Log.d(PermissionConstants.LOG_TAG, "Unable to detect current Activity.");

            errorCallback.onError(
                    "PermissionHandler.PermissionManager",
                    "Unable to detect current Android Activity.");
            return;
        }

        Map<Integer, Integer> requestResults = new HashMap<>();
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (Integer permission : permissions) {
            @PermissionConstants.PermissionStatus final int permissionStatus = determinePermissionStatus(permission, activity, activity);
            if (permissionStatus == PermissionConstants.PERMISSION_STATUS_GRANTED) {
                if (!requestResults.containsKey(permission)) {
                    requestResults.put(permission, PermissionConstants.PERMISSION_STATUS_GRANTED);
                }
                continue;
            }

            final List<String> names = PermissionUtils.getManifestNames(activity, permission);

            // check to see if we can find manifest names
            // if we can't add as unknown and continue
            if (names == null || names.isEmpty()) {
                if (!requestResults.containsKey(permission)) {
                    requestResults.put(permission, PermissionConstants.PERMISSION_STATUS_NOT_DETERMINED);
                }

                continue;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && permission == PermissionConstants.PERMISSION_GROUP_IGNORE_BATTERY_OPTIMIZATIONS) {
                // activityRegistry.addListener(
                // new ActivityResultListener(successCallback)
                // );
                requestResults.put(permission, PermissionConstants.PERMISSION_STATUS_NOT_DETERMINED);
                sendingIntentCount++;
                String packageName = activity.getPackageName();
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                activity.startActivityForResult(intent, PermissionConstants.PERMISSION_CODE_IGNORE_BATTERY_OPTIMIZATIONS);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && permission == PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW) {
                // activityRegistry.addListener(new ActivityResultListener(successCallback,
                // activity));
                requestResults.put(permission, PermissionConstants.PERMISSION_STATUS_NOT_DETERMINED);
                sendingIntentCount++;
//                String packageName = activity.getPackageName();
//                Intent intent = new Intent();
//                intent.setAction(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
//                intent.setData(Uri.parse("package:" + packageName));
//                activity.startActivityForResult(intent, PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW);
                onDisplayPopupPermission(activity);
            } else {
                permissionsToRequest.addAll(names);
            }
        }

        final String[] requestPermissions = permissionsToRequest.toArray(new String[0]);
        if (permissionsToRequest.size() > 0) {
            permissionRegistry.addListener(
                    new RequestPermissionsListener(
                            activity,
                            requestResults,
                            (Map<Integer, Integer> results) -> {
                                ongoing = false;
                                if(sendingIntentCount>0)
                                    successResult = results;
                                else
                                successCallback.onSuccess(results);
                            })
            );
//            activityRegistry.addListener(new PermissionManagerResult(new RequestPermissionsSuccessCallback() {
//                @Override
//                public void onSuccess(Map<Integer, Integer> results) {
//                    successCallback.onSuccess(results);
//                }
//            },activity));
            ongoing = true;

            ActivityCompat.requestPermissions(
                    activity,
                    requestPermissions,
                    PermissionConstants.PERMISSION_CODE);
        } else {
            ongoing = false;
            if (requestResults.size() > 0) {
                if(sendingIntentCount>0)
                    successResult = requestResults;
                else
                successCallback.onSuccess(requestResults);
            }
        }
    }

    @PermissionConstants.PermissionStatus
    private int determinePermissionStatus(
            @PermissionConstants.PermissionGroup int permission,
            Context context,
            @Nullable Activity activity) {

        if (permission == PermissionConstants.PERMISSION_GROUP_NOTIFICATION) {
            return checkNotificationPermissionStatus(context);
        }

        final List<String> names = PermissionUtils.getManifestNames(context, permission);

        if (names == null) {
            Log.d(PermissionConstants.LOG_TAG, "No android specific permissions needed for: " + permission);

            return PermissionConstants.PERMISSION_STATUS_GRANTED;
        }

        //if no permissions were found then there is an issue and permission is not set in Android manifest
        if (names.size() == 0) {
            Log.d(PermissionConstants.LOG_TAG, "No permissions found in manifest for: " + permission);
            return PermissionConstants.PERMISSION_STATUS_NOT_DETERMINED;
        }

        final boolean targetsMOrHigher = context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.M;

        for (String name : names) {
            // Only handle them if the client app actually targets a API level greater than M.
            if (targetsMOrHigher) {
                if (permission == PermissionConstants.PERMISSION_GROUP_IGNORE_BATTERY_OPTIMIZATIONS) {
                    String packageName = context.getPackageName();
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    // PowerManager.isIgnoringBatteryOptimizations has been included in Android M first.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                            return PermissionConstants.PERMISSION_STATUS_GRANTED;
                        } else {
                            return PermissionConstants.PERMISSION_STATUS_DENIED;
                        }
                    } else {
                        return PermissionConstants.PERMISSION_STATUS_RESTRICTED;
                    }
                }
                if (permission == PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.canDrawOverlays(context)) {
                            return PermissionConstants.PERMISSION_STATUS_GRANTED;
                        } else {
                            return PermissionConstants.PERMISSION_STATUS_DENIED;
                        }
                    } else {
                        return PermissionConstants.PERMISSION_STATUS_RESTRICTED;
                    }
                }
                final int permissionStatus = ContextCompat.checkSelfPermission(context, name);
                if (permissionStatus == PackageManager.PERMISSION_DENIED) {
                    if (!PermissionUtils.getRequestedPermissionBefore(context, name)) {
                        return PermissionConstants.PERMISSION_STATUS_NOT_DETERMINED;
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            PermissionUtils.isNeverAskAgainSelected(activity, name)) {
                        return PermissionConstants.PERMISSION_STATUS_NEVER_ASK_AGAIN;
                    } else {
                        return PermissionConstants.PERMISSION_STATUS_DENIED;
                    }
                } else if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                    return PermissionConstants.PERMISSION_STATUS_DENIED;
                }
            }
        }

        return PermissionConstants.PERMISSION_STATUS_GRANTED;
    }

    void shouldShowRequestPermissionRationale(
            int permission,
            Activity activity,
            ShouldShowRequestPermissionRationaleSuccessCallback successCallback,
            ErrorCallback errorCallback) {
        if (activity == null) {
            Log.d(PermissionConstants.LOG_TAG, "Unable to detect current Activity.");

            errorCallback.onError(
                    "PermissionHandler.PermissionManager",
                    "Unable to detect current Android Activity.");
            return;
        }

        List<String> names = PermissionUtils.getManifestNames(activity, permission);

        // if isn't an android specific group then go ahead and return false;
        if (names == null) {
            Log.d(PermissionConstants.LOG_TAG, "No android specific permissions needed for: " + permission);
            successCallback.onSuccess(false);
            return;
        }

        if (names.isEmpty()) {
            Log.d(PermissionConstants.LOG_TAG, "No permissions found in manifest for: " + permission + " no need to show request rationale");
            successCallback.onSuccess(false);
            return;
        }

        successCallback.onSuccess(ActivityCompat.shouldShowRequestPermissionRationale(activity, names.get(0)));
    }

    private int checkNotificationPermissionStatus(Context context) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        boolean isGranted = manager.areNotificationsEnabled();
        if (isGranted) {
            return PermissionConstants.PERMISSION_STATUS_GRANTED;
        }
        return PermissionConstants.PERMISSION_STATUS_DENIED;
    }


    private  void onDisplayPopupPermission(Activity activity) {
        if (!isMIUI()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW);
            return;
        }
        try {
            Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            intent.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity");
            intent.putExtra("extra_pkgname", activity.getPackageName());
            activity.startActivityForResult(intent, PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW);
            return;
        } catch (Exception ignore) {
        }
        try {
            // MIUI 5/6/7
            Intent localIntent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            localIntent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity");
            localIntent.putExtra("extra_pkgname", activity.getPackageName());
            activity.startActivityForResult(localIntent, PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW);
            return;
        } catch (Exception ignore) {
        }
        // Otherwise jump to application details
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
        intent.setData(uri);
        activity.startActivityForResult(intent, PermissionConstants.PERMISSION_GROUP_SYSTEM_ALERT_WINDOW);
    }


    private static boolean isMIUI() {
        String device = Build.MANUFACTURER;
        if (device.equalsIgnoreCase("xiaomi")) {
            try {
                Properties prop = new Properties();
                prop.load(new FileInputStream(new File(Environment.getRootDirectory(), "build.prop")));
                return prop.getProperty("ro.miui.ui.version.code", null) != null
                        || prop.getProperty("ro.miui.ui.version.name", null) != null
                        || prop.getProperty("ro.miui.internal.storage", null) != null;
            } catch (IOException e) {
                e.printStackTrace();
                return true;
            }

        }
        return false;
    }
    
}
