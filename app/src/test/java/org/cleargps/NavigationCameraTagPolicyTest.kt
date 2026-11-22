package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationCameraTagPolicyTest {
    @Test
    fun enforcementTypesAreClassifiedForMotorTraffic() {
        assertEquals(NavigationPoiCategory.SPEED_CAMERA, NavigationPoiCategory.fromEnforcement("maxspeed"))
        assertEquals(NavigationPoiCategory.SPEED_CAMERA, NavigationPoiCategory.fromEnforcement("average_speed"))
        assertEquals(NavigationPoiCategory.RED_LIGHT_CAMERA, NavigationPoiCategory.fromEnforcement("traffic_signals"))
        assertEquals(NavigationPoiCategory.TRAFFIC_CAMERA, NavigationPoiCategory.fromEnforcement("mobile_phone"))
        assertEquals(NavigationPoiCategory.TRAFFIC_CAMERA, NavigationPoiCategory.fromEnforcement("seatbelt"))
        assertEquals(NavigationPoiCategory.TRAFFIC_CAMERA, NavigationPoiCategory.fromEnforcement("access"))
    }

    @Test
    fun unrelatedSurveillanceAndNonVehicleEnforcementAreExcluded() {
        assertNull(NavigationPoiCategory.fromEnforcement("maxweight"))
        assertNull(NavigationPoiCategory.fromEnforcement("maxheight"))
        assertNull(NavigationPoiCategory.fromEnforcement("toll"))
        assertNull(NavigationPoiCategory.fromEnforcement("surveillance"))
        assertNull(
            NavigationPoiCategory.fromTags(
                mapOf("man_made" to "surveillance", "surveillance" to "public")
            )
        )
    }

    @Test
    fun speedCameraNodeIsRecognizedWithoutBeingOnTheRoadGeometry() {
        assertEquals(
            NavigationPoiCategory.SPEED_CAMERA,
            NavigationPoiCategory.fromTags(mapOf("highway" to "speed_camera"))
        )
    }
}
