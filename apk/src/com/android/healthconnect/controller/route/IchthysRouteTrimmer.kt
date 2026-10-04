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

package com.android.healthconnect.controller.route

import android.content.Context
import android.health.connect.datatypes.ExerciseRoute
import android.provider.Settings
import android.util.Log
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trims the ends of an exercise route before [RouteRequestActivity] hands it to the requesting
 * app, so the route doesn't reveal where the user lives.
 *
 * Mirrors `com.android.server.healthconnect.ichthys.RouteTrimmer` in the service, which applies
 * the same rule to `readRecords` and `getChangeLogs`: every location within the radius of the
 * first or last point is dropped, and a route with nothing left is returned empty. The dialog's
 * map still shows the user the full route; only the copy returned to the app is trimmed.
 */
object IchthysRouteTrimmer {

    private const val TAG = "IchthysRouteTrimmer"

    /**
     * Per-user [Settings.Secure] key: trim radius in metres, default [DEFAULT_RADIUS_METERS], `0`
     * disables. Keep in sync with `RouteTrimmer.SETTING_RADIUS_METERS` in the service.
     */
    const val SETTING_RADIUS_METERS = "ichthys_hc_route_trim_m"

    const val DEFAULT_RADIUS_METERS = 500

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun trim(context: Context, route: ExerciseRoute): ExerciseRoute {
        val radius =
            try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    SETTING_RADIUS_METERS,
                    DEFAULT_RADIUS_METERS,
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Failed to read $SETTING_RADIUS_METERS", e)
                DEFAULT_RADIUS_METERS
            }
        if (radius <= 0 || route.routeLocations.isEmpty()) {
            return route
        }
        val sorted = route.routeLocations.sortedBy { it.time }
        val first = sorted.first()
        val last = sorted.last()
        val kept =
            sorted.filter {
                distanceMeters(first, it) >= radius && distanceMeters(last, it) >= radius
            }
        return ExerciseRoute(kept)
    }

    /** Haversine great-circle distance. */
    private fun distanceMeters(a: ExerciseRoute.Location, b: ExerciseRoute.Location): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }
}
