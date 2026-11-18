package ai.omiro.openpocket;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import java.util.ArrayDeque;
import java.util.Locale;

/** Keeps a short foreground-only history so discovery time never becomes capture time. */
final class CaptureLocation implements LocationListener {
    private final LocationManager manager;
    private final ArrayDeque<Location> fixes = new ArrayDeque<>();

    CaptureLocation(Context context) { manager = context.getSystemService(LocationManager.class); }

    void start() {
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 0, this);
        } catch (SecurityException | IllegalArgumentException unavailable) { stop(); }
    }
    void stop() {
        manager.removeUpdates(this);
    }
    synchronized void forget() { fixes.clear(); }

    @Override public synchronized void onLocationChanged(Location fix) {
        fixes.addLast(new Location(fix));
        while (fixes.size() > 240) fixes.removeFirst();
    }
    synchronized String near(long started) {
        Location nearest = null;
        long distance = 120001;
        for (Location fix : fixes) {
            long delta = Math.abs(fix.getTime() - started);
            if (delta < distance) { nearest = fix; distance = delta; }
        }
        return nearest == null ? null : String.format(Locale.ROOT, "%.2f,%.2f",
            nearest.getLatitude(), nearest.getLongitude());
    }
}
