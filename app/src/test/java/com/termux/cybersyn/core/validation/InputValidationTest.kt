package com.termux.cybersyn.core.validation

import com.termux.cybersyn.core.model.ActionSpec
import com.termux.cybersyn.core.model.ContextSpec
import com.termux.cybersyn.core.model.ContextType
import com.termux.cybersyn.core.model.Profile
import com.termux.cybersyn.core.model.Task
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidationTest {
    @Test
    fun validateProfileRejectsEmptyNameMissingTaskAndEmptyContexts() {
        val errors = InputValidation.validateProfile(
            Profile(
                name = " ",
                enterTaskId = 0,
                contexts = emptyList()
            )
        )

        assertTrue(errors.any { it.field == "name" })
        assertTrue(errors.any { it.field == "enterTaskId" })
        assertTrue(errors.any { it.field == "contexts" })
    }

    @Test
    fun validateProfileRejectsCooldownOutsideSupportedRange() {
        val errors = InputValidation.validateProfile(
            Profile(
                name = "Too frequent",
                enterTaskId = 1,
                contexts = listOf(ContextSpec(ContextType.STATE)),
                cooldownSec = InputValidation.MAX_COOLDOWN_SEC + 1
            )
        )

        assertTrue(errors.any { it.field == "cooldownSec" })
    }

    @Test
    fun validateTaskRejectsInvalidPriorityAndEmptyActions() {
        val errors = InputValidation.validateTask(
            Task(
                name = "Bad priority",
                priority = 11,
                actions = emptyList()
            )
        )

        assertTrue(errors.any { it.field == "priority" })
        assertTrue(errors.any { it.field == "actions" })
    }

    @Test
    fun validateActionRejectsMissingType() {
        val errors = InputValidation.validateAction(ActionSpec(type = " "))

        assertTrue(errors.any { it.field == "type" })
    }
}
