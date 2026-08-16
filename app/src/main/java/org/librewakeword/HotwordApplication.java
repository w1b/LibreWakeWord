package org.librewakeword;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.librewakeword.detection.DetectionCoordinator;

public class HotwordApplication extends Application {
    private DetectionCoordinator detectionCoordinator;

    @Override
    public void onCreate() {
        super.onCreate();
        detectionCoordinator = new DetectionCoordinator(this);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                detectionCoordinator.onPackagesChanged();
            }
        }, packages, Context.RECEIVER_EXPORTED);
        ShizukuAutoArm.init(this);
    }

    public DetectionCoordinator getDetectionCoordinator() {
        if (detectionCoordinator == null) {
            throw new IllegalStateException("DetectionCoordinator is unavailable in this process");
        }
        return detectionCoordinator;
    }
}
