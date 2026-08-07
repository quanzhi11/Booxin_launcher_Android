package org.libsdl.app;

import android.app.Activity;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * SDL library initialization (SDL 3.4.14 Android bindings).
 */
public class SDL {

    public static void setupJNI() {
        SDLActivity.nativeSetupJNI();
        SDLAudioManager.nativeSetupJNI();
        SDLControllerManager.nativeSetupJNI();
    }

    public static void initialize() {
        setContext(null);

        SDLActivity.initialize();
        SDLAudioManager.initialize();
        SDLControllerManager.initialize();
    }

    public static void setContext(Activity context) {
        SDLAudioManager.setContext(context);
        mContext = context;
    }

    /** Booxin: allow non-Activity Context for audio/manager when Activity is unavailable. */
    public static void setContext(Context context) {
        if (context instanceof Activity) {
            setContext((Activity) context);
            return;
        }
        SDLAudioManager.setContext(context);
        // Keep last Activity if we only got an Application Context.
        if (mContext == null && context instanceof Activity) {
            mContext = (Activity) context;
        }
    }

    public static Activity getContext() {
        return mContext;
    }

    public static void loadLibrary(String libraryName) throws UnsatisfiedLinkError, SecurityException, NullPointerException {
        loadLibrary(libraryName, mContext);
    }

    public static void loadLibrary(String libraryName, Context context) throws UnsatisfiedLinkError, SecurityException, NullPointerException {
        if (libraryName == null) {
            throw new NullPointerException("No library name provided.");
        }

        try {
            Class<?> relinkClass = context.getClassLoader().loadClass("com.getkeepsafe.relinker.ReLinker");
            Class<?> relinkListenerClass = context.getClassLoader().loadClass("com.getkeepsafe.relinker.ReLinker$LoadListener");
            Class<?> contextClass = context.getClassLoader().loadClass("android.content.Context");
            Class<?> stringClass = context.getClassLoader().loadClass("java.lang.String");

            Method forceMethod = relinkClass.getDeclaredMethod("force");
            Object relinkInstance = forceMethod.invoke(null);
            Class<?> relinkInstanceClass = relinkInstance.getClass();

            Method loadMethod = relinkInstanceClass.getDeclaredMethod("loadLibrary", contextClass, stringClass, stringClass, relinkListenerClass);
            loadMethod.invoke(relinkInstance, context, libraryName, null, null);
        } catch (final Throwable e) {
            try {
                System.loadLibrary(libraryName);
            } catch (final UnsatisfiedLinkError ule) {
                throw ule;
            } catch (final SecurityException se) {
                throw se;
            }
        }
    }

    protected static Activity mContext;
}
