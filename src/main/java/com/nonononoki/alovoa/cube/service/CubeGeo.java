package com.nonononoki.alovoa.cube.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CubeOS location privacy helpers: coordinates are stored rounded to 2 decimal places (~1.1 km of
 * latitude) and distances are reported in whole kilometres, with 0 meaning "less than 1 km".
 */
public final class CubeGeo {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private CubeGeo() {
    }

    /** Rounds a coordinate to 2 decimal places (half-up), e.g. 52.51234 -> 52.51. */
    public static double round(double coordinate) {
        double r = BigDecimal.valueOf(coordinate).setScale(2, RoundingMode.HALF_UP).doubleValue();
        return r == 0.0 ? 0.0 : r; // no -0.0
    }

    public static boolean validLatitude(Double lat) {
        return lat != null && Double.isFinite(lat) && lat >= -90 && lat <= 90;
    }

    public static boolean validLongitude(Double lon) {
        return lon != null && Double.isFinite(lon) && lon >= -180 && lon <= 180;
    }

    /** Great-circle (haversine) distance in km. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Whole kilometres for display: 0 means "< 1 km", otherwise rounded to the nearest km (min 1). */
    public static int displayKm(double km) {
        if (km < 1.0) {
            return 0;
        }
        return (int) Math.max(1, Math.round(km));
    }
}
