package com.dreef3.weightlossapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoodEstimationEngineTest {
    @Test
    fun noOpEngineFailsUntilRuntimeIsConfigured() {
        val result = kotlinx.coroutines.runBlocking {
            NoOpFoodEstimationEngine().estimate(FoodEstimationRequest("path", 0))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun liteRtEngineFailsCleanlyWhenModelIsMissing() {
        val result = kotlinx.coroutines.runBlocking {
            LiteRtFoodEstimationEngine(modelFile = File("/does/not/exist.litertlm"))
                .estimate(FoodEstimationRequest("path", 0))
        }

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        if (exception is FoodEstimationException) {
            assertEquals(FoodEstimationError.ModelUnavailable, exception.error)
        } else {
            assertTrue(exception != null)
        }
    }
}
