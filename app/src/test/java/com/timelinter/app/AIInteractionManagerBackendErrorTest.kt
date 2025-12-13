package com.timelinter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AIInteractionManagerBackendErrorTest {

    @Test
    fun returnsPendingMessageWhenCodeIsPendingApproval() {
        val error = BackendHttpException(
            statusCode = 403,
            message = "pending",
            code = BackendAccessCode.PENDING_APPROVAL,
        )

        val parsed = AIInteractionManager.mapBackendHttpError(error)

        assertEquals(
            "Your account is pending approval. Please wait until it is activated.",
            parsed.userMessage
        )
        assertEquals(false, parsed.authExpired)
        assertEquals(emptyList<ToolCommand>(), parsed.tools)
    }

    @Test
    fun returnsRefusedMessageWhenCodeIsAccessRefused() {
        val error = BackendHttpException(
            statusCode = 403,
            message = "refused",
            code = BackendAccessCode.ACCESS_REFUSED,
        )

        val parsed = AIInteractionManager.mapBackendHttpError(error)

        assertEquals(
            "Access has been refused for this account. Please contact support if you believe this is an error.",
            parsed.userMessage
        )
        assertEquals(false, parsed.authExpired)
        assertEquals(emptyList<ToolCommand>(), parsed.tools)
    }

    @Test
    fun fallsBackToGenericMessageWhenCodeUnknown() {
        val error = BackendHttpException(
            statusCode = 500,
            message = "oops",
            code = null,
        )

        val parsed = AIInteractionManager.mapBackendHttpError(error)

        assertEquals("(Backend Error: HTTP 500)", parsed.userMessage)
        assertEquals(false, parsed.authExpired)
        assertEquals(emptyList<ToolCommand>(), parsed.tools)
    }
}

