/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.healthconnect.ichthys;

import android.annotation.Nullable;
import android.content.Context;
import android.health.connect.internal.datatypes.ExerciseRouteInternal;
import android.health.connect.internal.datatypes.ExerciseRouteInternal.LocationInternal;
import android.health.connect.internal.datatypes.ExerciseSessionRecordInternal;
import android.health.connect.internal.datatypes.RecordInternal;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Trims the start and end of exercise routes before they are handed to a client app, so that a
 * run or ride recorded from the front door does not reveal where the user lives.
 *
 * <p><b>What is removed.</b> Every route location within {@link #DEFAULT_RADIUS_METERS} (great
 * circle distance) of the session's first <em>or</em> last recorded location. Points in the
 * middle of the route that pass back near home are removed too, so a loop that crosses the start
 * mid-way doesn't put it back. If nothing is left the route is returned empty rather than
 * {@code null}: a {@code null} route with {@code hasRoute == true} means "route exists but needs
 * consent", which would only push the app into the route request flow.
 *
 * <p><b>Where it applies.</b> Only on the paths that hand records to a client app —
 * {@code readRecords} and {@code getChangeLogs} in {@code HealthConnectServiceImpl}. Storage,
 * backup/restore, export/import and the Health Connect UI (callers holding
 * {@code MANAGE_HEALTH_DATA}) see the full route; the user's own view of their data is not
 * degraded. Nor is a record the calling app wrote itself: it already has that route, and an app
 * that reads its own sessions back to sync or edit them must not get a different one. The route
 * request dialog in the Health Connect APK trims its result separately with the same rule
 * ({@code RouteRequestActivity}).
 *
 * <p><b>Setting.</b> {@link #SETTING_RADIUS_METERS} in {@link Settings.Secure}, per user: an
 * integer radius in metres. Unset means {@link #DEFAULT_RADIUS_METERS}; {@code 0} (or any
 * non-positive value) turns trimming off.
 */
public final class RouteTrimmer {

    private static final String TAG = "IchthysRouteTrimmer";

    /**
     * Per-user {@link Settings.Secure} key: trim radius in metres, default {@link
     * #DEFAULT_RADIUS_METERS}, {@code 0} disables. Keep in sync with {@code RouteRequestActivity}
     * in the Health Connect APK.
     */
    public static final String SETTING_RADIUS_METERS = "ichthys_hc_route_trim_m";

    /** Default trim radius, used when the setting is unset or unreadable. */
    public static final int DEFAULT_RADIUS_METERS = 500;

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private RouteTrimmer() {}

    /**
     * Trims, in place, the route of every {@link ExerciseSessionRecordInternal} in {@code
     * records} that {@code callingPackageName} does not own. Records of other types are left
     * alone. Never throws: a failure leaves the records as they were and is logged, because a
     * read must not fail over a privacy filter.
     *
     * @param user the user whose setting applies (the caller's user)
     * @param callingPackageName the app the records are read for, as it is before the records'
     *     package names are masked
     */
    public static void trimRoutes(
            Context context,
            UserHandle user,
            @Nullable String callingPackageName,
            @Nullable List<RecordInternal<?>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        boolean anyRoute = false;
        for (RecordInternal<?> record : records) {
            if (record instanceof ExerciseSessionRecordInternal session
                    && session.getRoute() != null
                    && !isOwnedBy(session, callingPackageName)) {
                anyRoute = true;
                break;
            }
        }
        if (!anyRoute) {
            return;
        }
        int radius = getRadiusMeters(context, user);
        if (radius <= 0) {
            return;
        }
        for (RecordInternal<?> record : records) {
            if (record instanceof ExerciseSessionRecordInternal session
                    && !isOwnedBy(session, callingPackageName)) {
                ExerciseRouteInternal route = session.getRoute();
                if (route != null) {
                    session.setRoute(trim(route, radius));
                }
            }
        }
    }

    private static boolean isOwnedBy(RecordInternal<?> record, @Nullable String packageName) {
        return packageName != null && packageName.equals(record.getPackageName());
    }

    /** Returns a copy of {@code route} without the points within {@code radiusMeters} of its ends. */
    public static ExerciseRouteInternal trim(ExerciseRouteInternal route, double radiusMeters) {
        List<LocationInternal> sorted = new ArrayList<>(route.getRouteLocations());
        if (sorted.isEmpty() || radiusMeters <= 0) {
            return route;
        }
        sorted.sort(Comparator.comparingLong(LocationInternal::getTime));
        LocationInternal first = sorted.get(0);
        LocationInternal last = sorted.get(sorted.size() - 1);

        List<LocationInternal> kept = new ArrayList<>(sorted.size());
        for (LocationInternal location : sorted) {
            if (distanceMeters(first, location) < radiusMeters
                    || distanceMeters(last, location) < radiusMeters) {
                continue;
            }
            kept.add(location);
        }
        return new ExerciseRouteInternal(kept);
    }

    private static int getRadiusMeters(Context context, UserHandle user) {
        final long token = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.getInt(
                    context.createContextAsUser(user, /* flags= */ 0).getContentResolver(),
                    SETTING_RADIUS_METERS,
                    DEFAULT_RADIUS_METERS);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to read " + SETTING_RADIUS_METERS, e);
            return DEFAULT_RADIUS_METERS;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Haversine great-circle distance. */
    private static double distanceMeters(LocationInternal a, LocationInternal b) {
        double lat1 = Math.toRadians(a.getLatitude());
        double lat2 = Math.toRadians(b.getLatitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
