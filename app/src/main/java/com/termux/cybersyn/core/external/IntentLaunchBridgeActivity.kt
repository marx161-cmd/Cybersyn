package com.termux.cybersyn.core.external

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import com.termux.cybersyn.core.logging.AppLogger

class IntentLaunchBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_INTENT_URI)?.let(::parseSimpleIntentUri)
            ?: intent.getStringExtra(EXTRA_INTENT_URI)?.let { uri ->
                runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            }
            ?: Intent(intent.getStringExtra(EXTRA_ACTION)).apply {
                intent.getStringExtra(EXTRA_PACKAGE)?.let(::setPackage)
                intent.getStringExtra(EXTRA_COMPONENT)?.let { ComponentName.unflattenFromString(it)?.let(::setComponent) }
            }
        runCatching {
            startActivity(target)
        }.onFailure { error ->
            AppLogger.error(TAG, "Bridge launch failed", error)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun parseSimpleIntentUri(uri: String): Intent? {
        if (!uri.startsWith("#Intent;") || !uri.endsWith(";end")) return null
        val fields = uri.removePrefix("#Intent;").removeSuffix(";end")
            .split(';')
            .mapNotNull { field ->
                val idx = field.indexOf('=')
                if (idx <= 0) null else field.substring(0, idx) to field.substring(idx + 1)
            }
            .toMap()
        val action = fields["action"]
        val component = fields["component"]?.let(ComponentName::unflattenFromString)
        if (action == null && component == null) return null
        return Intent(action).apply { component?.let(::setComponent) }
    }

    companion object {
        const val EXTRA_INTENT_URI = "com.termux.cybersyn.extra.INTENT_URI"
        const val EXTRA_PACKAGE = "com.termux.cybersyn.extra.PACKAGE"
        const val EXTRA_ACTION = "com.termux.cybersyn.extra.ACTION"
        const val EXTRA_COMPONENT = "com.termux.cybersyn.extra.COMPONENT"
        private const val TAG = "IntentLaunchBridge"
    }
}
