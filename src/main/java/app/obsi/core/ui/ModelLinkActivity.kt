package app.obsi.core.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.obsi.core.Brand
import app.obsi.core.ai.ModelBridgeService
import app.obsi.core.ai.ModelLinks
import app.obsi.core.ai.OnPhone

/**
 * Asks the person whether another app, like Obsidian, may use the AI model on this phone. Opened by
 * obsi-ai://link?name=…&state=…&return=obsidian://…
 *
 * The answer goes back only to an obsidian:// address, so another app cannot ask for a key to be
 * sent to itself. The key goes back once; only its hash stays here.
 */
class ModelLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val ret = data?.getQueryParameter("return").orEmpty()
        val state = data?.getQueryParameter("state").orEmpty()
        val name = ModelLinks.cleanName(data?.getQueryParameter("name").orEmpty())
        if (!ret.startsWith("obsidian://") || !Regex("^[A-Za-z0-9_-]{16,128}$").matches(state)) {
            finish()
            return
        }

        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                val phone by OnPhone.state.collectAsState()
                LaunchedEffect(Unit) { OnPhone.check(ctx) }
                if (phone.builtIn >= 0 && !phone.ready) {
                    AlertDialog(
                        onDismissRequest = { reply(ret, state, mapOf("error" to "no-model")) },
                        title = { Text("No AI model yet") },
                        text = { Text("$name asked to use the AI model on this phone, but there is none yet. Get one in ${Brand.name}, Settings, AI, then link again.") },
                        confirmButton = { TextButton(onClick = { reply(ret, state, mapOf("error" to "no-model")) }) { Text("OK") } },
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = { reply(ret, state, mapOf("error" to "denied")) },
                        title = { Text("Let $name use the AI model?") },
                        text = {
                            Text(
                                if (!phone.ready) "Checking the AI model on this phone…"
                                else "$name can send short pieces of text to the model on this phone and get answers back. " +
                                    "Nothing leaves the phone. You can remove it any time in ${Brand.name}, Settings, AI.",
                            )
                        },
                        confirmButton = {
                            TextButton(enabled = phone.ready, onClick = {
                                val token = ModelLinks.add(ctx, name)
                                ModelBridgeService.start(ctx)
                                reply(ret, state, mapOf("token" to token, "app" to Brand.name))
                            }) { Text("Allow") }
                        },
                        dismissButton = { TextButton(onClick = { reply(ret, state, mapOf("error" to "denied")) }) { Text("Not now") } },
                    )
                }
            }
        }
    }

    private fun reply(ret: String, state: String, extra: Map<String, String>) {
        val b = Uri.parse(ret).buildUpon().appendQueryParameter("state", state)
        for ((k, v) in extra) b.appendQueryParameter(k, v)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, b.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }
}
