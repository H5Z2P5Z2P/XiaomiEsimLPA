package im.angry.openeuicc.miuihook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.telephony.IccOpenLogicalChannelResponse;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MiuiEsimSlotHook implements IXposedHookLoadPackage {
    private static final String TAG = "MiuiEsimSlotHook";
    private static final String PHONE_PACKAGE = "com.android.phone";
    private static final String EUICC_PACKAGE = "com.miui.euicc";
    private static final String EUICC_MAIN_ACTIVITY = "com.miui.euicc.ui.main.MainActivity";
    private static final String SETTING_SELECTED_SLOT = "openeuicc_manage_slot";
    private static final String SETTING_ENABLE_ESIM_FOR_USER = "is_enable_esim_for_user";
    private static final String DEVICE_CAPABILITIES_PREF = hashKey("Device_Capabilities");
    private static final String DEFAULT_SLOT_PREF = hashKey("DEFAULT_SIM_SLOT_ID_KEY");
    private static final int DEFAULT_SLOT = 1;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (PHONE_PACKAGE.equals(lpparam.packageName)) {
            hookPhonePackage(lpparam.classLoader);
        } else if (EUICC_PACKAGE.equals(lpparam.packageName)) {
            hookEuiccPackage(lpparam.classLoader);
        }
    }

    private void hookPhonePackage(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                "com.android.phone.MiuiPhoneUtils",
                classLoader,
                "enterEsimManager",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        final Context phoneContext = getPhoneContext(classLoader);
                        final Activity activity = findResumedActivity();
                        if (phoneContext == null) {
                            log("PhoneGlobals context is unavailable; falling back to slot 1");
                            return null;
                        }
                        if (activity == null || activity.isFinishing()) {
                            log("No resumed activity found; launching manager without chooser");
                            launchEuiccManager(phoneContext, readSelectedSlot(phoneContext, DEFAULT_SLOT));
                            return null;
                        }
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showSlotChooser(activity, phoneContext);
                            }
                        });
                        return null;
                    }
                });
    }

    private void hookEuiccPackage(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                "com.miui.euicc.LpaApplication",
                classLoader,
                "c",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Context context = getCurrentApplicationContext();
                        ensureEsimEnabled(context);
                        return readSelectedSlot(context, DEFAULT_SLOT);
                    }
                });

        XposedHelpers.findAndHookMethod(
                "k4.b",
                classLoader,
                "a",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Context context = getCurrentApplicationContext();
                        ensureEsimEnabled(context);
                        return readSelectedSlot(context, DEFAULT_SLOT);
                    }
                });

        hookApduMethod(classLoader);
    }

    private void hookApduMethod(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                "z3.b",
                classLoader,
                "e",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = getApduContext(param.thisObject);
                        if (!isApduSupported(classLoader, context)) {
                            return null;
                        }
                        TelephonyManager telephonyManager = getApduTelephonyManager(param.thisObject, context);
                        if (telephonyManager == null) {
                            log("APDU hook could not resolve TelephonyManager");
                            return null;
                        }
                        byte[] aid = getApduAid(param.thisObject);
                        if (aid == null || aid.length == 0) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.OpenChannelException",
                                    new Class<?>[]{String.class},
                                    new Object[]{"AID is null"});
                        }
                        int slot = readSelectedSlot(context, DEFAULT_SLOT);
                        Object response = invokeSlotMethod(
                                telephonyManager,
                                "iccOpenLogicalChannelBySlot",
                                new Class<?>[]{int.class, String.class, int.class},
                                new Object[]{slot, toHex(aid), 4});
                        if (response != null) {
                            setApduChannelResponse(param.thisObject, response);
                        }
                        IccOpenLogicalChannelResponse channelResponse = (IccOpenLogicalChannelResponse) response;
                        if (channelResponse == null) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.OpenChannelException",
                                    new Class<?>[]{String.class},
                                    new Object[]{"Channel is null"});
                        }
                        if (channelResponse.getChannel() == -1) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.OpenChannelException",
                                    new Class<?>[]{String.class},
                                    new Object[]{"Invalid Channel"});
                        }
                        int status = channelResponse.getStatus();
                        if (status == 1) {
                            log("Opened logical channel on slot " + slot + ", channel=" + channelResponse.getChannel());
                            return null;
                        }
                        if (status == 2) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.OpenChannelException",
                                    new Class<?>[]{String.class},
                                    new Object[]{"No logical channel available"});
                        }
                        if (status == 3) {
                            throw new NoSuchElementException("Attempt to select unknown AID: " + toHex(aid));
                        }
                        throw newTargetException(
                                classLoader,
                                "com.miui.euicc.core.common.apdu.exceptions.OpenChannelException",
                                new Class<?>[]{String.class},
                                new Object[]{"Unknown open channel error"});
                    }
                });

        XposedHelpers.findAndHookMethod(
                "z3.b",
                classLoader,
                "k",
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = getApduContext(param.thisObject);
                        TelephonyManager telephonyManager = getApduTelephonyManager(param.thisObject, context);
                        IccOpenLogicalChannelResponse channelResponse = getApduChannelResponse(param.thisObject);
                        if (telephonyManager == null || channelResponse == null) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.InvalidResponseApduException",
                                    new Class<?>[]{byte[].class},
                                    new Object[]{null});
                        }
                        int slot = readSelectedSlot(context, DEFAULT_SLOT);
                        Object response = invokeSlotMethod(
                                telephonyManager,
                                "iccTransmitApduLogicalChannelBySlot",
                                new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class, int.class, String.class},
                                new Object[]{
                                        slot,
                                        channelResponse.getChannel(),
                                        param.args[0],
                                        param.args[1],
                                        param.args[2],
                                        param.args[3],
                                        param.args[4],
                                        param.args[5]
                                });
                        if (!(response instanceof String)) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.InvalidResponseApduException",
                                    new Class<?>[]{byte[].class},
                                new Object[]{null});
                        }
                        String rapdu = (String) response;
                        if (rapdu.length() < 4) {
                            throw newTargetException(
                                    classLoader,
                                    "com.miui.euicc.core.common.apdu.exceptions.InvalidResponseApduException",
                                    new Class<?>[]{byte[].class},
                                    new Object[]{null});
                        }
                        int length = rapdu.length() - 4;
                        return new String[]{rapdu.substring(0, length), rapdu.substring(length)};
                    }
                });

        XposedHelpers.findAndHookMethod(
                "z3.b",
                classLoader,
                "b",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = getApduContext(param.thisObject);
                        TelephonyManager telephonyManager = getApduTelephonyManager(param.thisObject, context);
                        IccOpenLogicalChannelResponse channelResponse = getApduChannelResponse(param.thisObject);
                        if (telephonyManager == null || channelResponse == null) {
                            return null;
                        }
                        int slot = readSelectedSlot(context, DEFAULT_SLOT);
                        invokeSlotMethod(
                                telephonyManager,
                                "iccCloseLogicalChannelBySlot",
                                new Class<?>[]{int.class, int.class},
                                new Object[]{slot, channelResponse.getChannel()});
                        return null;
                    }
                });
    }

    private void showSlotChooser(final Activity activity, final Context phoneContext) {
        final List<SlotChoice> choices = buildSlotChoices(activity);
        if (choices.isEmpty()) {
            launchEuiccManager(phoneContext, readSelectedSlot(phoneContext, DEFAULT_SLOT));
            return;
        }
        final String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Choose eSIM slot")
                .setItems(labels, (dialog, which) -> launchEuiccManager(phoneContext, choices.get(which).slotIndex))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchEuiccManager(Context context, int slotIndex) {
        ensureEsimEnabled(context);
        persistSelectedSlot(context, slotIndex);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(EUICC_PACKAGE, EUICC_MAIN_ACTIVITY));
        intent.putExtra("openeuicc_selected_slot", slotIndex);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            context.startActivity(intent);
            log("Launched MIUI eSIM manager for slot " + slotIndex);
        } catch (Throwable t) {
            log("Failed to launch MIUI eSIM manager: " + t);
        }
    }

    private List<SlotChoice> buildSlotChoices(Context context) {
        List<SlotChoice> slots = new ArrayList<>();
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        SubscriptionManager subscriptionManager = context.getSystemService(SubscriptionManager.class);
        if (telephonyManager == null) {
            return slots;
        }
        Object[] infos = getUiccSlotInfo(telephonyManager);
        int slotCount = infos != null ? infos.length : Math.max(telephonyManager.getActiveModemCount(), 1);
        int currentSlot = readSelectedSlot(context, DEFAULT_SLOT);
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            Object info = infos != null && slotIndex < infos.length ? infos[slotIndex] : null;
            SubscriptionInfo subInfo = null;
            try {
                if (subscriptionManager != null) {
                    subInfo = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex);
                }
            } catch (Throwable t) {
                log("Unable to read subscription info for slot " + slotIndex + ": " + t);
            }
            StringBuilder label = new StringBuilder();
            label.append("SIM ").append(slotIndex + 1).append(" (slot ").append(slotIndex).append(")");
            if (slotIndex == currentSlot) {
                label.append(" [current]");
            }
            if (subInfo != null && !TextUtils.isEmpty(subInfo.getDisplayName())) {
                label.append(" - ").append(subInfo.getDisplayName());
            }
            label.append(" - ").append(simStateToString(telephonyManager.getSimState(slotIndex)));
            if (info != null) {
                label.append(" - ").append(cardStateToString(getCardStateInfo(info)));
                if (isEuiccSlot(info)) {
                    label.append(" - euicc");
                }
            }
            slots.add(new SlotChoice(slotIndex, label.toString()));
        }
        return slots;
    }

    private Object[] getUiccSlotInfo(TelephonyManager telephonyManager) {
        try {
            Method method = telephonyManager.getClass().getMethod("getUiccSlotsInfo");
            Object result = method.invoke(telephonyManager);
            return result instanceof Object[] ? (Object[]) result : null;
        } catch (Throwable t) {
            log("Unable to read UICC slot info: " + t);
            return null;
        }
    }

    private Context getPhoneContext(ClassLoader classLoader) {
        try {
            Class<?> phoneGlobals = XposedHelpers.findClass("com.android.phone.PhoneGlobals", classLoader);
            return (Context) XposedHelpers.callStaticMethod(phoneGlobals, "getInstance");
        } catch (Throwable t) {
            log("Unable to obtain PhoneGlobals context: " + t);
            return getCurrentApplicationContext();
        }
    }

    private Context getCurrentApplicationContext() {
        try {
            Object application = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            log("Unable to obtain current application: " + t);
            return null;
        }
    }

    private Activity findResumedActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            Object activitiesObject = XposedHelpers.getObjectField(activityThread, "mActivities");
            if (!(activitiesObject instanceof Map)) {
                return null;
            }
            Map<?, ?> activities = (Map<?, ?>) activitiesObject;
            for (Object record : activities.values()) {
                Activity activity = (Activity) XposedHelpers.getObjectField(record, "activity");
                boolean paused = XposedHelpers.getBooleanField(record, "paused");
                if (activity != null && !paused) {
                    return activity;
                }
            }
        } catch (Throwable t) {
            log("Unable to find resumed activity: " + t);
        }
        return null;
    }

    private boolean isApduSupported(ClassLoader classLoader, Context context) {
        if (context == null) {
            return false;
        }
        try {
            Class<?> helper = XposedHelpers.findClass("g4.a", classLoader);
            Object result = XposedHelpers.callStaticMethod(helper, "j", context);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (Throwable t) {
            log("Unable to query APDU support, assuming enabled: " + t);
            return true;
        }
    }

    private Object invokeSlotMethod(TelephonyManager telephonyManager, String methodName, Class<?>[] parameterTypes, Object[] args) throws Throwable {
        Method method = telephonyManager.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(telephonyManager, args);
    }

    private Context getApduContext(Object apduService) {
        Context context = (Context) getFieldValueByType(apduService, Context.class, false);
        return context != null ? context : getCurrentApplicationContext();
    }

    private TelephonyManager getApduTelephonyManager(Object apduService, Context context) {
        TelephonyManager telephonyManager = (TelephonyManager) getFieldValueByType(apduService, TelephonyManager.class, true);
        if (telephonyManager != null) {
            return telephonyManager;
        }
        if (context == null) {
            return null;
        }
        try {
            telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                setFieldValueByType(apduService, TelephonyManager.class, true, telephonyManager);
            }
        } catch (Throwable t) {
            log("Unable to obtain TelephonyManager from context: " + t);
        }
        return telephonyManager;
    }

    private IccOpenLogicalChannelResponse getApduChannelResponse(Object apduService) {
        return (IccOpenLogicalChannelResponse) getFieldValueByType(apduService, IccOpenLogicalChannelResponse.class, true);
    }

    private void setApduChannelResponse(Object apduService, Object response) {
        setFieldValueByType(apduService, IccOpenLogicalChannelResponse.class, true, response);
    }

    private byte[] getApduAid(Object apduService) {
        return (byte[]) getFieldValueByType(apduService, byte[].class, false);
    }

    private Object getFieldValueByType(Object target, Class<?> fieldType, boolean allowAssignable) {
        Field field = findFieldByType(target != null ? target.getClass() : null, fieldType, allowAssignable);
        if (field == null || target == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            log("Unable to read field " + field.getName() + " from " + target.getClass().getName() + ": " + t);
            return null;
        }
    }

    private void setFieldValueByType(Object target, Class<?> fieldType, boolean allowAssignable, Object value) {
        Field field = findFieldByType(target != null ? target.getClass() : null, fieldType, allowAssignable);
        if (field == null || target == null) {
            return;
        }
        try {
            field.set(target, value);
        } catch (Throwable t) {
            log("Unable to write field " + field.getName() + " on " + target.getClass().getName() + ": " + t);
        }
    }

    private Field findFieldByType(Class<?> startClass, Class<?> fieldType, boolean allowAssignable) {
        for (Class<?> current = startClass; current != null && current != Object.class; current = current.getSuperclass()) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                Class<?> candidateType = field.getType();
                boolean matches = allowAssignable ? fieldType.isAssignableFrom(candidateType) : candidateType == fieldType;
                if (!matches) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable t) {
                    log("Unable to make field accessible: " + current.getName() + "#" + field.getName() + ": " + t);
                }
                return field;
            }
        }
        return null;
    }

    private void persistSelectedSlot(Context context, int slotIndex) {
        if (context == null) {
            return;
        }
        try {
            Settings.Secure.putInt(context.getContentResolver(), SETTING_SELECTED_SLOT, slotIndex);
        } catch (Throwable t) {
            log("Unable to persist selected slot to Settings.Secure: " + t);
        }
        try {
            Context euiccContext = context.createPackageContext(EUICC_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            Context deviceContext = euiccContext.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deviceContext.getSharedPreferences(DEVICE_CAPABILITIES_PREF, Context.MODE_PRIVATE);
            prefs.edit().putInt(DEFAULT_SLOT_PREF, slotIndex).apply();
        } catch (Throwable t) {
            log("Unable to mirror selected slot into MIUI shared prefs: " + t);
        }
    }

    private void ensureEsimEnabled(Context context) {
        if (context == null) {
            return;
        }
        try {
            int current = Settings.Secure.getInt(context.getContentResolver(), SETTING_ENABLE_ESIM_FOR_USER, 0);
            if (current != 1) {
                boolean updated = Settings.Secure.putInt(context.getContentResolver(), SETTING_ENABLE_ESIM_FOR_USER, 1);
                log("Forced is_enable_esim_for_user=1, updated=" + updated + ", previous=" + current);
            }
        } catch (Throwable t) {
            log("Unable to force is_enable_esim_for_user=1: " + t);
        }
    }

    private int readSelectedSlot(Context context, int fallback) {
        if (context == null) {
            return fallback;
        }
        try {
            return Settings.Secure.getInt(context.getContentResolver(), SETTING_SELECTED_SLOT, fallback);
        } catch (Throwable t) {
            log("Unable to read selected slot: " + t);
            return fallback;
        }
    }

    private static Throwable newTargetException(String className, Class<?>[] parameterTypes, Object[] args, ClassLoader classLoader) throws Throwable {
        return newTargetException(classLoader, className, parameterTypes, args);
    }

    private static Throwable newTargetException(ClassLoader classLoader, String className, Class<?>[] parameterTypes, Object[] args) throws Throwable {
        Class<?> exceptionClass = XposedHelpers.findClass(className, classLoader);
        Constructor<?> constructor = exceptionClass.getConstructor(parameterTypes);
        Object exception = constructor.newInstance(args);
        return (Throwable) exception;
    }

    private static String hashKey(String value) {
        return Integer.toHexString(value.hashCode());
    }

    private static String toHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        char[] out = new char[data.length * 2];
        final char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0F];
        }
        return new String(out);
    }

    private static String simStateToString(int simState) {
        switch (simState) {
            case TelephonyManager.SIM_STATE_READY:
                return "ready";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return "pin";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return "puk";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return "locked";
            case TelephonyManager.SIM_STATE_ABSENT:
                return "absent";
            case TelephonyManager.SIM_STATE_NOT_READY:
                return "not-ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED:
                return "perm-disabled";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR:
                return "io-error";
            case TelephonyManager.SIM_STATE_CARD_RESTRICTED:
                return "restricted";
            default:
                return "unknown";
        }
    }

    private static String cardStateToString(int cardState) {
        switch (cardState) {
            case 1:
                return "card-absent";
            case 2:
                return "card-present";
            case 3:
                return "card-error";
            case 4:
                return "card-restricted";
            default:
                return "card-unknown";
        }
    }

    private static int getCardStateInfo(Object slotInfo) {
        try {
            Method method = slotInfo.getClass().getMethod("getCardStateInfo");
            Object result = method.invoke(slotInfo);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Throwable t) {
            log("Unable to read slot card state: " + t);
            return 0;
        }
    }

    private static boolean isEuiccSlot(Object slotInfo) {
        try {
            Method method = slotInfo.getClass().getMethod("getIsEuicc");
            Object result = method.invoke(slotInfo);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            log("Unable to read eUICC flag: " + t);
            return false;
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    private static final class SlotChoice {
        final int slotIndex;
        final String label;

        SlotChoice(int slotIndex, String label) {
            this.slotIndex = slotIndex;
            this.label = label;
        }
    }
}
