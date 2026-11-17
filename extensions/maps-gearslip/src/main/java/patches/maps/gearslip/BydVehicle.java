package patches.maps.gearslip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the vehicle's state of charge and remaining range from byd-open-remote and hands them to
 * {@link BydEtaText} for the navigation card's status line.
 *
 * <p>The UI path never blocks: {@link #decorate} reads a cache only. The cache is filled by the
 * provider's change broadcast, and an asynchronous query refills it when it goes stale. Data older
 * than {@link #MAX_AGE_MS} is treated as unknown and renders nothing, so a car parked overnight
 * never shows yesterday's range.
 */
public final class BydVehicle {
    private static final String TAG = "BydVehicle";
    public static final String AUTHORITY = "com.bydopenremote.app.vehicle";
    public static final String ACTION_STATUS_CHANGED = "com.bydopenremote.app.action.VEHICLE_STATUS_CHANGED";
    public static final String PERMISSION = "com.bydopenremote.app.permission.READ_VEHICLE";
    /** Built on demand: {@code Uri.parse} is an Android stub and throws in plain JVM tests. */
    static Uri statusUri() {
        return Uri.parse("content://" + AUTHORITY + "/status");
    }
    static final String COLUMN_SOC = "soc";
    static final String COLUMN_RANGE = "range_km";
    static final String COLUMN_TS = "ts";
    static final String COLUMN_UNIT = "distance_unit";
    static final long MAX_AGE_MS = 15L * 60L * 1000L;

    private static final AtomicBoolean ATTACHED = new AtomicBoolean();
    /**
     * TEMPORARY: renders fixed values so the display path can be verified before byd-open-remote
     * publishes the provider. False in every real build; flipped only for a demo APK.
     */
    static boolean DEMO = true;
    private static final ExecutorService QUERIES = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "byd-vehicle");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile int soc = -1;
    private static volatile int rangeKm = -1;
    private static volatile long timestamp;
    private static volatile String unit = "km";
    private static volatile long lastQueryAt;
    /** Remaining route distance in kilometres, -1 when the navigation strip could not be read. */
    private static volatile double remainingKm = -1;

    private BydVehicle() {
    }

    /** The status line with the arrival battery, or the original when nothing fresh is known. */
    public static String decorate(String original) {
        if (!ATTACHED.get()) attach(appContext());
        if (!DEMO && !fresh(timestamp, System.currentTimeMillis())) return original;
        int charge = DEMO ? 62 : soc;
        int range = DEMO ? 310 : rangeKm;
        String rangeUnit = DEMO ? "km" : unit;
        double rangeInKm = BydEtaText.kilometres(range, rangeUnit);
        int arrival = rangeInKm > 0
                ? BydEtaText.arrivalPercent(charge, (int) Math.round(rangeInKm), remainingKm)
                : -1;
        return BydEtaText.withArrival(original, charge, arrival);
    }

    /** Remaining route distance, in kilometres, as last read from the navigation strip. */
    static void setRemainingKm(double value) {
        remainingKm = value;
    }

    /**
     * Maps has no Context at the card binder, and this hook must not depend on a separate startup
     * patch. {@code ActivityThread.currentApplication()} is the process-wide Application.
     */
    private static Context appContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object application = thread.getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** True when {@code ts} is recent enough to show. */
    static boolean fresh(long ts, long now) {
        return ts > 0 && now - ts <= MAX_AGE_MS;
    }

    /** Validates and caches one reading; out-of-range values are ignored rather than shown. */
    static void onStatus(int socPercent, int rangeKmValue, long ts, String distanceUnit) {
        if (socPercent < -1 || socPercent > 100) return;
        if (rangeKmValue < -1) return;
        if (ts <= 0) return;
        soc = socPercent;
        rangeKm = rangeKmValue;
        timestamp = ts;
        unit = distanceUnit == null || distanceUnit.isEmpty() ? "km" : distanceUnit;
    }

    /** Registers the change receiver once; safe to call from any thread and any number of times. */
    public static void attach(Context context) {
        if (context == null || !ATTACHED.compareAndSet(false, true)) return;
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            IntentFilter filter = new IntentFilter(ACTION_STATUS_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(RECEIVER, filter);
            if (app instanceof android.app.Application) {
                ((android.app.Application) app).registerActivityLifecycleCallbacks(LIFECYCLE);
            }
            mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(TICK);
            refresh(app);
        } catch (RuntimeException denied) {
            // A missing permission or an unsupported registration must not affect Maps at all.
            Log.w(TAG, "Vehicle status receiver unavailable", denied);
        }
    }

    private static volatile Handler mainHandler;
    private static volatile WeakReference<android.app.Activity> resumed;
    /** The suffix currently painted, so a view Maps repaints can be re-decorated without doubling it. */
    private static volatile String appliedSuffix = "";

    private static final android.app.Application.ActivityLifecycleCallbacks LIFECYCLE =
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(android.app.Activity activity) {
                    resumed = new WeakReference<>(activity);
                }

                @Override public void onActivityPaused(android.app.Activity activity) {
                    WeakReference<android.app.Activity> current = resumed;
                    if (current != null && current.get() == activity) resumed = null;
                }

                @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }

                @Override public void onActivityStarted(android.app.Activity activity) { }

                @Override public void onActivityStopped(android.app.Activity activity) { }

                @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }

                @Override public void onActivityDestroyed(android.app.Activity activity) { }
            };

    /**
     * The phone's own navigation strip ("18 min" / "15 mi · 4:54 AM") is a plain TextView that Maps
     * rewrites every second, so it is decorated on a timer rather than at a call site. The car UI
     * uses the turn card and needs none of this.
     */
    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            try {
                WeakReference<android.app.Activity> current = resumed;
                android.app.Activity activity = current == null ? null : current.get();
                if (activity != null) paint(activity);
            } catch (RuntimeException ignored) {
                // Never let the decoration affect Maps' own rendering.
            } finally {
                Handler handler = mainHandler;
                if (handler != null) handler.postDelayed(this, 1500L);
            }
        }
    };

    /** "15 mi" / "1.2 km" / "350 ft" near the ETA, converted to kilometres; -1 when unreadable. */
    private static double siblingDistanceKm(TextView time) {
        android.view.View node = time;
        // The ETA time and distance are often siblings one row apart, sometimes nested; three
        // levels up covers both without scanning the whole screen.
        for (int level = 0; level < 4 && node != null; level++) {
            android.view.ViewParent parent = node.getParent();
            if (!(parent instanceof android.view.ViewGroup)) break;
            double km = distanceIn((android.view.ViewGroup) parent, time, 2);
            if (km >= 0) return km;
            node = (android.view.View) parent;
        }
        return -1;
    }

    private static double distanceIn(android.view.ViewGroup group, android.view.View time, int depth) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child == time) continue;
            if (child instanceof TextView) {
                CharSequence text = ((TextView) child).getText();
                if (text != null) {
                    java.util.regex.Matcher matcher = DISTANCE.matcher(text.toString());
                    if (matcher.find()) {
                        try {
                            double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                            double km = BydEtaText.kilometres(value, matcher.group(2));
                            if (km >= 0) return km;
                        } catch (NumberFormatException unreadable) {
                            // Keep looking at the other texts in the row.
                        }
                    }
                }
            }
            if (depth > 0 && child instanceof android.view.ViewGroup) {
                double km = distanceIn((android.view.ViewGroup) child, time, depth - 1);
                if (km >= 0) return km;
            }
        }
        return -1;
    }

    /** A distance with its unit, as Maps writes it into the navigation strip. */
    private static final java.util.regex.Pattern DISTANCE = java.util.regex.Pattern.compile(
            "([0-9]+(?:[.,][0-9]+)?)\\s*(mi|km|m|ft|miles|mile|kilometres|kilometers|kilometre|kilometer|feet|foot)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The battery box: its own view, so the charge reads as state rather than as route text. */
    private static volatile WeakReference<TextView> box;
    private static volatile WeakReference<android.app.Activity> boxOwner;

    private static void showBatteryBox(android.app.Activity activity) {
        int charge = DEMO ? 62 : soc;
        if (charge < 0) return;
        TextView view = box == null ? null : box.get();
        WeakReference<android.app.Activity> owner = boxOwner;
        if (view == null || owner == null || owner.get() != activity || view.getParent() == null) {
            view = createBatteryBox(activity, charge);
            if (view == null) return;
            box = new WeakReference<>(view);
            boxOwner = new WeakReference<>(activity);
        } else {
            view.setText(charge + "%");
        }
    }

    private static TextView createBatteryBox(android.app.Activity activity, int charge) {
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        float density = activity.getResources().getDisplayMetrics().density;
        // Shaped like Maps' own speed box: a rounded square with a big value and a small unit,
        // so the charge reads as vehicle state rather than as route text.
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        TextView value = new TextView(activity);
        value.setText(String.valueOf(charge));
        value.setTextColor(0xFFFFFFFF);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f);
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setGravity(android.view.Gravity.CENTER);
        TextView percent = new TextView(activity);
        percent.setText("%");
        percent.setTextColor(0xFFB0B0B0);
        percent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
        percent.setGravity(android.view.Gravity.CENTER);
        box.addView(value);
        box.addView(percent);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xE6000000);
        background.setCornerRadius(12 * density);
        box.setBackground(background);
        int size = Math.round(52 * density);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                size, size, android.view.Gravity.BOTTOM | android.view.Gravity.START);
        // Beside the speed box, in the free map area: clear of the zoom column above it.
        params.leftMargin = Math.round(80 * density);
        params.bottomMargin = Math.round(127 * density);
        try {
            root.addView(box, params);
        } catch (RuntimeException rejected) {
            Log.w(TAG, "Battery box could not be added", rejected);
            return null;
        }
        return value;
    }

    private static void paint(android.app.Activity activity) {
        int id = activity.getResources().getIdentifier(
                "navigation_time_remaining_label", "id", activity.getPackageName());
        if (id == 0) return;
        TextView view = activity.findViewById(id);
        if (view == null) return;
        CharSequence current = view.getText();
        if (current == null || current.length() == 0) return;
        String text = current.toString();
        double distance = siblingDistanceKm(view);
        if (distance >= 0) setRemainingKm(distance);
        // The card shows the expected charge as an icon, so the ETA line itself stays untouched.
        int level = chargeLevel();
        paintIcon(view, level);
    }

    /** Expected charge on arrival, or the current charge when the distance is unknown; -1 for none. */
    static int chargeLevel() {
        if (!DEMO && !fresh(timestamp, System.currentTimeMillis())) return -1;
        int charge = DEMO ? 62 : soc;
        String rangeUnit = DEMO ? "km" : unit;
        int range = DEMO ? 310 : rangeKm;
        double rangeInKm = BydEtaText.kilometres(range, rangeUnit);
        if (rangeInKm > 0) {
            int arrival = BydEtaText.arrivalPercent(charge, (int) Math.round(rangeInKm), remainingKm);
            if (arrival >= 0) return arrival;
        }
        return charge;
    }

    private static volatile WeakReference<BydBatteryIcon> icon;
    private static volatile WeakReference<TextView> iconOwner;

    /** Puts the gauge at the end of the ETA line, once per view. */
    private static void paintIcon(TextView view, int level) {
        if (level < 0) return;
        BydBatteryIcon gauge = icon == null ? null : icon.get();
        WeakReference<TextView> owner = iconOwner;
        if (gauge == null || owner == null || owner.get() != view) {
            gauge = new BydBatteryIcon(view.getResources().getDisplayMetrics().density);
            icon = new WeakReference<>(gauge);
            iconOwner = new WeakReference<>(view);
            int gap = Math.round(6 * view.getResources().getDisplayMetrics().density);
            view.setCompoundDrawablePadding(gap);
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, gauge, null);
        }
        gauge.setCharge(level);
    }

    /** Queries the provider off the UI thread; the answer lands in the cache for the next bind. */
    public static void refresh(Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (now - lastQueryAt < 2000L) return;
        lastQueryAt = now;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context target = app;
        try {
            QUERIES.execute(() -> query(target));
        } catch (RuntimeException rejected) {
            Log.w(TAG, "Vehicle status query rejected", rejected);
        }
    }

    private static void query(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(statusUri(), null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return;
            onStatus(
                    value(cursor, COLUMN_SOC, -1),
                    value(cursor, COLUMN_RANGE, -1),
                    cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TS)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_UNIT)));
        } catch (RuntimeException unavailable) {
            // No provider, no permission, or a malformed row: keep whatever the broadcast gave us.
            Log.w(TAG, "Vehicle status unavailable", unavailable);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static int value(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh(context);
        }
    };

    /** Clears the cache; used by tests and when the feature is switched off. */
    static void reset() {
        soc = -1;
        rangeKm = -1;
        timestamp = 0;
        unit = "km";
        lastQueryAt = 0;
        remainingKm = -1;
    }
}
