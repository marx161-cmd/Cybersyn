package com.termux.cybersyn.core.actions

import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionResult

class TaskerUnsupportedAction : Action {
    override val id = "tasker.unsupported"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val code = args["taskerCode"].orEmpty().ifBlank { "unknown" }
        return ActionResult.Failure("Unsupported imported Tasker action: $code")
    }
}
