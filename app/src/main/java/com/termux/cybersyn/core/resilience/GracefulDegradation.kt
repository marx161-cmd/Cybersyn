package com.termux.cybersyn.core.resilience

import com.termux.cybersyn.core.contexts.ContextEvent
import com.termux.cybersyn.core.contexts.ContextSource
import com.termux.cybersyn.core.contexts.ContextSourceRegistry
import com.termux.cybersyn.core.engine.Action
import com.termux.cybersyn.core.engine.ActionCategory
import com.termux.cybersyn.core.engine.ActionContext
import com.termux.cybersyn.core.engine.ActionRegistry
import com.termux.cybersyn.core.engine.ActionResult
import com.termux.cybersyn.core.logging.AppLogger
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Graceful degradation handlers for missing components.
 * Prevents crashes when actions or context sources are not registered.
 */
object GracefulDegradation {
    private const val TAG = "GracefulDegradation"
    
    /**
     * Get an action from registry, or return an explicit failure if missing.
     */
    fun getActionOrStub(actionId: String): Action {
        val action = ActionRegistry.get(actionId)
        if (action == null) {
            AppLogger.warn(TAG, "Action '$actionId' not found in registry")
            return MissingAction(actionId)
        }
        return action
    }
    
    /**
     * Get a context source from registry, or return a non-matching source if missing.
     */
    fun getContextSourceOrStub(contextType: String): ContextSource {
        val source = ContextSourceRegistry.get(contextType)
        if (source == null) {
            AppLogger.warn(TAG, "Context source '$contextType' not found in registry")
            return MissingContextSource(contextType)
        }
        return source
    }
    
    /**
     * Missing action that fails honestly instead of reporting success.
     */
    private class MissingAction(private val actionId: String) : Action {
        override val id: String = actionId
        override val category = ActionCategory.SYSTEM
        
        override suspend fun run(
            ctx: ActionContext,
            args: Map<String, String>
        ): ActionResult {
            AppLogger.warn(TAG, "Missing action executed: $actionId")
            return ActionResult.Failure("Action '$actionId' is not registered")
        }
    }
    
    /**
     * Missing context source that never matches.
     */
    private class MissingContextSource(private val contextType: String) : ContextSource {
        override val type: String = contextType
        
        override fun events(app: Context): Flow<ContextEvent> {
            AppLogger.warn(TAG, "Missing context source: $contextType")
            return flowOf(ContextEvent(contextType, false, emptyMap()))
        }
    }
}
