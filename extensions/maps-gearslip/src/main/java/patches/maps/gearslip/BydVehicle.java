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
import android.view.View;
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
    static boolean DEMO = false;
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
    /** Maps' own car surface, set by the projection renderer while a host is attached. */
    private static volatile WeakReference<View> carSurface;

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

    /** True when {@code ts} is recent enough to show; seconds are tolerated for old payloads. */
    static boolean fresh(long ts, long now) {
        if (ts <= 0) return false;
        long millis = ts < 100_000_000_000L ? ts * 1000L : ts;
        return now - millis <= MAX_AGE_MS;
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
            // One line that answers "which Maps build am I running?" without guessing.
            Log.i(TAG, "build=" + (DEMO ? "demo" : "real") + " demo=" + DEMO);
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
                WeakReference<View> car = carSurface;
                View surface = car == null ? null : car.get();
                if (surface != null) paintCar(surface);
            } catch (RuntimeException ignored) {
                // Never let the decoration affect Maps' own rendering.
            } finally {
                Handler handler = mainHandler;
                // 700 ms: Maps repaints the ETA card on its own schedule, so a slower tick leaves a
                // visible gap where its text wins. The work per tick is a small view-tree walk.
                if (handler != null) handler.postDelayed(this, 700L);
            }
        }
    };

    /** Registers the car surface; the renderer calls this when the host has one. */
    public static void setCarSurface(View surface) {
        carSurface = surface == null ? null : new WeakReference<>(surface);
        paintCar(surface);
    }

    public static void clearCarSurface() {
        carSurface = null;
        TextView chip = carChip == null ? null : carChip.get();
        if (chip != null && chip.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) chip.getParent()).removeView(chip);
        }
        carChip = null;
    }

    /** First distance in the car card ("8.5 km"), in kilometres; -1 when there is none. */
    private static double distanceInTree(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                java.util.regex.Matcher matcher = DISTANCE.matcher(text.toString());
                if (matcher.find()) {
                    try {
                        double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
                        double km = BydEtaText.kilometres(value, matcher.group(2));
                        if (km >= 0) return km;
                    } catch (NumberFormatException unreadable) {
                        // Keep looking.
                    }
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                double km = distanceInTree(group.getChildAt(i));
                if (km >= 0) return km;
            }
        }
        return -1;
    }

    /** A chip of our own, so Maps never repaints the charge away. */
    private static volatile WeakReference<TextView> carChip;

    private static void paintCar(View root) {
        if (!(root instanceof android.view.ViewGroup)) return;
        TextView duration = findDuration(root);
        if (duration == null) return;
        double distance = distanceInTree(root);
        if (distance >= 0) setRemainingKm(distance);
        int level = chargeLevel();
        if (level < 0) return;
        TextView chip = ensureChip((android.view.ViewGroup) root, duration.getCurrentTextColor());
        String label = level + "%";
        if (!label.contentEquals(chip.getText())) chip.setText(label);
        placeChip(chip, duration, root);
    }

    private static TextView findDuration(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && looksLikeDuration(text.toString())) return (TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findDuration(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView ensureChip(android.view.ViewGroup root, int color) {
        TextView chip = carChip == null ? null : carChip.get();
        if (chip != null && chip.getParent() == root) return chip;
        float density = root.getResources().getDisplayMetrics().density;
        chip = new TextView(root.getContext());
        chip.setTextColor(color);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chip.setGravity(android.view.Gravity.CENTER);
        int horizontal = Math.round(9 * density), vertical = Math.round(3 * density);
        chip.setPadding(horizontal, vertical, horizontal, vertical);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xF2000000);
        background.setCornerRadius(7 * density);
        chip.setBackground(background);
        try {
            root.addView(chip, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (RuntimeException rejected) {
            Log.w(TAG, "Battery chip could not be added", rejected);
            return chip;
        }
        carChip = new WeakReference<>(chip);
        return chip;
    }

    /** Sits just above the ETA card's top-left corner, measured from the card itself. */
    private static void placeChip(TextView chip, TextView card, View root) {
        try {
            int[] cardAt = new int[2];
            int[] rootAt = new int[2];
            card.getLocationInWindow(cardAt);
            root.getLocationInWindow(rootAt);
            chip.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED);
            float gap = chip.getResources().getDisplayMetrics().density * 6f;
            float x = cardAt[0] - rootAt[0];
            float y = cardAt[1] - rootAt[1] - chip.getMeasuredHeight() - gap;
            chip.setTranslationX(Math.max(0, x));
            chip.setTranslationY(Math.max(0, y));
        } catch (RuntimeException unplaceable) {
            Log.w(TAG, "Battery chip could not be placed", unplaceable);
        }
    }

    /** A line carrying a route duration ("19 min", "1 hr 12 min") and no charge of ours yet. */
    private static boolean looksLikeDuration(String value) {
        if (value.isEmpty() || value.contains(SUFFIX_TAIL)) return false;
        if (value.length() > 24) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.contains("min") || lower.contains("hr") || lower.contains("h "))) return false;
        for (int i = 0; i < lower.length(); i++) if (Character.isDigit(lower.charAt(i))) return true;
        return false;
    }

    private static final String SUFFIX_TAIL = " • ";

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

    private static void paint(android.app.Activity activity) {
        int id = activity.getResources().getIdentifier(
                "navigation_time_remaining_label", "id", activity.getPackageName());
        if (id == 0) return;
        TextView view = activity.findViewById(id);
        if (view == null) return;
        CharSequence current = view.getText();
        if (current == null || current.length() == 0) return;
        String text = current.toString();
        if (text.equals(lastApplied)) return;
        double distance = siblingDistanceKm(view);
        if (distance >= 0) setRemainingKm(distance);
        // Self-healing: if the cache is stale (never read, or a grant arrived later), re-query.
        // refresh() is debounced, so ticking once a second cannot flood the provider.
        if (!DEMO && !fresh(timestamp, System.currentTimeMillis())) refresh(activity.getApplicationContext());
        int level = chargeLevel();
        if (level < 0) return;
        // "19 min • 47%": the expected charge, as text, in Maps' own line.
        String line = text + " • " + level + "%";
        lastApplied = line;
        view.setText(line);
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

    /** The decorated line we last wrote, so Maps' own repaint can be told apart from ours. */
    private static volatile String lastApplied = "";

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
