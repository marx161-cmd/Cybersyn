package com.termux.cybersyn.core

import com.termux.cybersyn.core.actions.ActionMetadataRegistry
import com.termux.cybersyn.core.actions.registerActionMetadata
import com.termux.cybersyn.core.contexts.ContextSourceRegistry
import com.termux.cybersyn.core.engine.ActionRegistry
import com.termux.cybersyn.core.engine.FlowControl
import com.termux.cybersyn.core.engine.SUB_TASK_ACTION_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRegistriesTest {
    // Actions handled directly by the TaskRunner (not via ActionRegistry).
    private val engineHandledActions = setOf(SUB_TASK_ACTION_ID) + FlowControl.ALL

    @Test
    fun everyUiMetadataActionHasRuntimeImplementation() {
        registerActionMetadata()
        registerCoreRuntime()

        val missing = ActionMetadataRegistry.all()
            .map { it.id }
            .filter { it !in engineHandledActions && ActionRegistry.get(it) == null }

        assertTrue("Missing runtime actions: $missing", missing.isEmpty())
    }

    /**
     * UI metadata is optional. An action with no catalog entry simply isn't offered in the
     * action picker and displays as its raw id — which is the identifier bundles, the CLI
     * and EXPLAIN use anyway. Relay- and CLI-driven actions are authored by tooling, not
     * by hand, so requiring catalog strings for them only produced a standing failure.
     *
     * What this guards instead: an action may be left out of the UI *deliberately*, by
     * listing it in [RUNTIME_ONLY_ACTION_IDS], and that list must stay honest — an entry
     * for an action that no longer exists, or one that has since been given metadata, is
     * stale bookkeeping and fails here.
     */
    @Test
    fun runtimeActionsWithoutUiMetadataAreDeclaredRuntimeOnly() {
        registerActionMetadata()
        registerCoreRuntime()

        val metadataIds = ActionMetadataRegistry.all().map { it.id }.toSet()
        val undeclared = ActionRegistry.allIds()
            .filter { it !in metadataIds && it !in RUNTIME_ONLY_ACTION_IDS }

        assertTrue(
            "Actions have no UI metadata and are not declared runtime-only. Either add " +
                "metadata or list them in RUNTIME_ONLY_ACTION_IDS: $undeclared",
            undeclared.isEmpty(),
        )

        val stale = RUNTIME_ONLY_ACTION_IDS.filter { it in metadataIds || ActionRegistry.get(it) == null }
        assertTrue("RUNTIME_ONLY_ACTION_IDS is stale, remove: $stale", stale.isEmpty())
    }

    @Test
    fun dynamicFormMetadataUsesRuntimeArgumentKeys() {
        registerActionMetadata()

        assertFieldKeys("brightness.set", "brightness")
        assertFieldKeys("screenshot.take", "path")
        assertFieldKeys("file.read", "path", "var")
        assertFieldKeys("file.write", "path", "text")
        assertFieldKeys("file.append", "path", "text")
        assertFieldKeys("file.list", "path", "var", "pattern")
        assertFieldKeys(
            "http.request",
            "method", "url", "query", "headers", "authorization", "body", "body_file", "content_type",
            "response_var", "status_var", "headers_var", "output_file", "max_response_bytes", "redirects",
            "allow_http", "timeout_sec", "connect_timeout_sec", "read_timeout_sec", "write_timeout_sec",
            "call_timeout_sec",
        )
        assertFieldKeys("http.get", "url", "var", "allow_http")
        assertFieldKeys("http.post", "url", "data", "var", "allow_http")
    }

    @Test
    fun coreContextSourcesIncludeLiveLocationSource() {
        registerCoreRuntime()

        val registered = ContextSourceRegistry.all().map { it.type }.toSet()

        assertTrue("Location context source must be registered: $registered", "location" in registered)
    }

    private fun assertFieldKeys(actionId: String, vararg expected: String) {
        val metadata = ActionMetadataRegistry.get(actionId)
        assertTrue("Missing metadata for $actionId", metadata != null)
        assertEquals(
            "$actionId field keys",
            expected.toList(),
            metadata!!.fields.map { it.key },
        )
    }
}
