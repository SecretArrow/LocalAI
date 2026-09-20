package com.localai.runtime.ui.screens.apidocs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.SectionCard

private data class Endpoint(val method: String, val path: String, val note: String)

private val nativeEndpoints = listOf(
    Endpoint("GET", "/api/health", "Liveness check — no authentication required"),
    Endpoint("GET", "/api/status", "Server uptime, running models, request counters"),
    Endpoint("GET", "/api/models", "All installed models"),
    Endpoint("GET", "/api/models/{id}", "Single model details"),
    Endpoint("POST", "/api/models/{id}/start", "Load and start a model"),
    Endpoint("POST", "/api/models/{id}/stop", "Unload a model"),
    Endpoint("POST", "/api/models/{id}/restart", "Restart a model"),
    Endpoint("GET", "/api/runtime", "Backend availability summary"),
    Endpoint("GET", "/api/backends", "Detected inference backends"),
    Endpoint("GET", "/api/devices", "Paired devices (currently returns an empty list)"),
    Endpoint("GET", "/api/downloads", "Server-side downloads (currently returns an empty list)"),
)

private val openAiEndpoints = listOf(
    Endpoint("GET", "/v1/models", "OpenAI model listing"),
    Endpoint("POST", "/v1/chat/completions", "Chat completion — supports stream=true (SSE)"),
    Endpoint("POST", "/v1/completions", "Legacy text completion"),
    Endpoint("POST", "/v1/embeddings", "Not supported — returns 501"),
)

/** Built-in API documentation with examples generated from the current settings. */
@Composable
fun ApiDocsScreen(container: AppContainer, navController: NavHostController) {
    val settings by container.settingsRepository.flow
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val base = "http://${settings.apiHost}:${settings.apiPort}"

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("API documentation", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Base URL: $base — replace ${settings.apiHost} with this device's LAN IP " +
                    "(see the Server screen) when calling from another machine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(title = "Authentication") {
                Text(
                    when (settings.apiAuthMode) {
                        AuthMode.NONE ->
                            "Authentication is disabled — anyone who can reach the server can call the API."
                        AuthMode.API_KEY ->
                            "Requests must include the X-API-Key header (Authorization: Bearer is also accepted)."
                        AuthMode.BEARER ->
                            "Requests must include the Authorization: Bearer <token> header."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "GET /api/health never requires authentication.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "Endpoints") {
                Text("Native", style = MaterialTheme.typography.titleSmall)
                nativeEndpoints.forEach { EndpointRow(it) }
                Text("OpenAI-compatible", style = MaterialTheme.typography.titleSmall)
                openAiEndpoints.forEach { EndpointRow(it) }
            }

            SectionCard(title = "curl") {
                CodeBlock(
                    title = "List models",
                    code = "curl $base/v1/models",
                )
                CodeBlock(
                    title = "Chat completion",
                    code = buildString {
                        append("curl -X POST $base/v1/chat/completions \\\n")
                        append("  -H \"Content-Type: application/json\" \\\n")
                        if (settings.apiAuthMode == AuthMode.BEARER) {
                            append("  -H \"Authorization: Bearer <token>\" \\\n")
                        } else if (settings.apiAuthMode == AuthMode.API_KEY) {
                            append("  -H \"X-API-Key: <token>\" \\\n")
                        }
                        append("  -d '{\"model\":\"<model-id>\"," +
                            "\"messages\":[{\"role\":\"user\",\"content\":\"Hello!\"}],\"stream\":false}'")
                    },
                )
                CodeBlock(
                    title = "Text completion",
                    code = buildString {
                        append("curl -X POST $base/v1/completions \\\n")
                        append("  -H \"Content-Type: application/json\" \\\n")
                        if (settings.apiAuthMode == AuthMode.BEARER) {
                            append("  -H \"Authorization: Bearer <token>\" \\\n")
                        } else if (settings.apiAuthMode == AuthMode.API_KEY) {
                            append("  -H \"X-API-Key: <token>\" \\\n")
                        }
                        append("  -d '{\"model\":\"<model-id>\",\"prompt\":\"Once upon a time\",\"max_tokens\":64}'")
                    },
                )
            }

            SectionCard(title = "Python (openai SDK)") {
                CodeBlock(
                    code = """
                        from openai import OpenAI

                        client = OpenAI(
                            base_url="$base/v1",
                            api_key="<token>",  # any value when auth is disabled
                        )

                        response = client.chat.completions.create(
                            model="<model-id>",
                            messages=[{"role": "user", "content": "Hello!"}],
                        )
                        print(response.choices[0].message.content)
                    """.trimIndent(),
                )
            }

            SectionCard(title = "JavaScript") {
                CodeBlock(
                    code = buildString {
                        append("const res = await fetch(\"$base/v1/chat/completions\", {\n")
                        append("  method: \"POST\",\n")
                        append("  headers: {\n")
                        append("    \"Content-Type\": \"application/json\"")
                        if (settings.apiAuthMode != AuthMode.NONE) {
                            append(",\n    \"Authorization\": \"Bearer <token>\"")
                        }
                        append("\n  },\n")
                        append("  body: JSON.stringify({\n")
                        append("    model: \"<model-id>\",\n")
                        append("    messages: [{ role: \"user\", content: \"Hello!\" }],\n")
                        append("  }),\n")
                        append("});\n")
                        append("const data = await res.json();\n")
                        append("console.log(data.choices[0].message.content);")
                    },
                )
            }

            SectionCard(title = "Notes") {
                Text(
                    "• POST /v1/embeddings returns 501 — embeddings are not available in this build.\n" +
                        "• A built-in web console is served at the server root ($base/).\n" +
                        "• Streaming responses use server-sent events (SSE) with OpenAI chunk schema.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EndpointRow(endpoint: Endpoint) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MethodBadge(endpoint.method)
        Column(Modifier.weight(1f)) {
            Text(
                endpoint.path,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                endpoint.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MethodBadge(method: String) {
    val color = if (method == "GET") {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            method,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CodeBlock(title: String? = null, code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                    )
                }
            }
            SelectionContainer {
                Text(
                    code,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
