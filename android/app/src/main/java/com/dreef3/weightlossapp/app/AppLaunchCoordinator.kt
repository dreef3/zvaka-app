package com.dreef3.weightlossapp.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppLaunchCoordinator {
    private val _pendingCameraLaunchRequestId = MutableStateFlow<Long?>(null)
    val pendingCameraLaunchRequestId: StateFlow<Long?> = _pendingCameraLaunchRequestId

    @Synchronized
    fun requestCameraLaunch() {
        nextRequestId += 1L
        _pendingCameraLaunchRequestId.value = nextRequestId
    }

    @Synchronized
    fun consumeCameraLaunchRequest(requestId: Long) {
        if (_pendingCameraLaunchRequestId.value == requestId) {
            _pendingCameraLaunchRequestId.value = null
        }
    }

    private companion object {
        private var nextRequestId: Long = 0L
    }
}
