package com.nonononoki.alovoa.cube;

import com.nonononoki.alovoa.cube.service.CubeGeo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CubeGeoTest {

    @Test
    void roundsToTwoDecimals() {
        assertEquals(52.52, CubeGeo.round(52.51678));
        assertEquals(13.40, CubeGeo.round(13.40449));
        assertEquals(6.46, CubeGeo.round(6.455)); // half-up
        assertEquals(-3.38, CubeGeo.round(-3.3791));
        assertEquals(0.0, CubeGeo.round(-0.001));
        assertEquals("0.0", Double.toString(CubeGeo.round(-0.001))); // no negative zero
        assertEquals(90.0, CubeGeo.round(89.999));
    }

    @Test
    void roundedPrecisionIsAboutOneKilometre() {
        // two points that round to the same stored location are within ~0.8 km of it
        double lat = 52.516, lon = 13.404;
        double err = CubeGeo.distanceKm(lat, lon, CubeGeo.round(lat), CubeGeo.round(lon));
        assertTrue(err < 0.8, "rounding error " + err);
    }

    @Test
    void validatesRanges() {
        assertTrue(CubeGeo.validLatitude(-90.0));
        assertFalse(CubeGeo.validLatitude(90.5));
        assertFalse(CubeGeo.validLatitude(Double.NaN));
        assertFalse(CubeGeo.validLatitude(null));
        assertTrue(CubeGeo.validLongitude(180.0));
        assertFalse(CubeGeo.validLongitude(-180.01));
        assertFalse(CubeGeo.validLongitude(Double.POSITIVE_INFINITY));
    }

    @Test
    void displayDistanceIsWholeKmWithZeroForUnderOne() {
        assertEquals(0, CubeGeo.displayKm(0.0));
        assertEquals(0, CubeGeo.displayKm(0.99));
        assertEquals(1, CubeGeo.displayKm(1.0));
        assertEquals(1, CubeGeo.displayKm(1.49));
        assertEquals(2, CubeGeo.displayKm(1.5));
        assertEquals(43, CubeGeo.displayKm(42.7));
    }

    @Test
    void haversine() {
        // Lagos (6.45, 3.39) to Ibadan (7.38, 3.93): ~119 km
        double km = CubeGeo.distanceKm(6.45, 3.39, 7.38, 3.93);
        assertTrue(km > 115 && km < 125, "got " + km);
    }
}
