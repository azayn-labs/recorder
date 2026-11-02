package com.azayn.recorder.daemon;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Makes a bare app_process JVM (running as uid 2000 / shell) look enough like an app
 * that framework APIs (AudioRecord, AudioPolicy, AudioManager) accept it.
 * Adapted from scrcpy's FakeContext/Workarounds.
 */
public final class Env {
    public static final String PACKAGE_NAME = "com.android.shell";
    public static final int SHELL_UID = 2000;

    private static Object activityThread;
    private static Class<?> activityThreadClass;
    private static Context context;

    private Env() {
    }

    public static synchronized void init() throws Exception {
        if (context != null) {
            return;
        }
        Looper.prepareMainLooper();

        activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        activityThread = ctor.newInstance();

        Field current = activityThreadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);

        Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(activityThread, true);

        tryFillConfigurationController();
        tryFillAppInfo();

        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        Context system = (Context) getSystemContext.invoke(activityThread);
        context = new ShellContext(system);
    }

    public static Context context() {
        return context;
    }

    private static void tryFillConfigurationController() {
        try {
            Class<?> ccClass = Class.forName("android.app.ConfigurationController");
            Class<?> internal = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> c = ccClass.getDeclaredConstructor(internal);
            c.setAccessible(true);
            Object cc = c.newInstance(activityThread);
            Field f = activityThreadClass.getDeclaredField("mConfigurationController");
            f.setAccessible(true);
            f.set(activityThread, cc);
        } catch (Throwable t) {
            Log.d("fillConfigurationController: " + t);
        }
    }

    private static void tryFillAppInfo() {
        try {
            Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> c = bindDataClass.getDeclaredConstructor();
            c.setAccessible(true);
            Object bindData = c.newInstance();
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = PACKAGE_NAME;
            Field appInfo = bindDataClass.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(bindData, info);
            Field bound = activityThreadClass.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, bindData);
        } catch (Throwable t) {
            Log.d("fillAppInfo: " + t);
        }
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID).setPackageName(PACKAGE_NAME).build();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @SuppressWarnings("unused")
        public int getDeviceId() {
            return 0;
        }
    }
}
