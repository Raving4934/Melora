package com.leyu.melora.ui.common

import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetBoundaryFlingTest {
    @Test
    fun onlyUnusedUpwardVelocityIsConsumed() = runBlocking {
        assertEquals(
            Velocity(0f, -1200f),
            SheetBoundaryFlingConnection.onPostFling(Velocity(0f, -800f), Velocity(300f, -1200f)),
        )
    }

    @Test
    fun downwardDismissAndHorizontalGesturesAreUnchanged() = runBlocking {
        assertEquals(
            Velocity.Zero,
            SheetBoundaryFlingConnection.onPostFling(Velocity.Zero, Velocity(300f, 1200f)),
        )
        assertEquals(
            Velocity.Zero,
            SheetBoundaryFlingConnection.onPostFling(Velocity(0f, -2000f), Velocity.Zero),
        )
    }
}
