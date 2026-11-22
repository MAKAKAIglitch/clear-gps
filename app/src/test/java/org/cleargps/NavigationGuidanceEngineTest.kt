package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuidanceEngineTest {
    private val route = NavigationRouteAlternative(
        coordinates = listOf(
            NavigationCoordinate(60.0, 24.0),
            NavigationCoordinate(60.0, 24.001),
            NavigationCoordinate(60.0, 24.002)
        ),
        distanceMeters = 220.0,
        durationSeconds = 60.0,
        steps = listOf(
            NavigationManeuverStep(
                instruction = "Начните движение",
                distanceMeters = 110.0,
                coordinate = NavigationCoordinate(60.0, 24.0),
                routeIndex = 0,
                maneuverType = "depart",
                modifier = null
            ),
            NavigationManeuverStep(
                instruction = "Поверните направо",
                distanceMeters = 110.0,
                coordinate = NavigationCoordinate(60.0, 24.001),
                routeIndex = 1,
                maneuverType = "turn",
                modifier = "right"
            ),
            NavigationManeuverStep(
                instruction = "Вы прибыли",
                distanceMeters = 0.0,
                coordinate = NavigationCoordinate(60.0, 24.002),
                routeIndex = 2,
                maneuverType = "arrive",
                modifier = null
            )
        )
    )

    @Test
    fun progressStartsWithFirstUpcomingTurnInsteadOfDepartureInstruction() {
        val progress = NavigationGuidanceEngine.snapshot(
            NavigationCoordinate(60.0, 24.0),
            route
        )
        assertTrue(progress != null)
        assertEquals(1, progress!!.stepIndex)
        assertEquals("Поверните направо", progress.instruction)
        assertEquals(route.steps[1].coordinate, progress.maneuverCoordinate)
    }

    @Test
    fun progressSelectsUpcomingManeuverAndEstimatesDistance() {
        val progress = NavigationGuidanceEngine.snapshot(
            NavigationCoordinate(60.0, 24.0005),
            route
        )
        assertTrue(progress != null)
        assertEquals(1, progress!!.stepIndex)
        assertTrue(progress.distanceToManeuverMeters < 40.0)
        assertTrue(progress.remainingDistanceMeters > 100.0)
    }

    @Test
    fun progressMovesForwardToNextManeuverAndNeverMovesBack() {
        val position = NavigationCoordinate(60.0, 24.0015)
        val progress = NavigationGuidanceEngine.snapshot(position, route, previousStepIndex = 1)
        assertTrue(progress != null)
        assertTrue(progress!!.stepIndex >= 1)
    }

    @Test
    fun progressReportsDistanceFromRoute() {
        val progress = NavigationGuidanceEngine.snapshot(
            NavigationCoordinate(60.001, 24.001),
            route
        )
        assertTrue(progress != null)
        assertTrue(progress!!.offRouteDistanceMeters > 90.0)
    }
}
