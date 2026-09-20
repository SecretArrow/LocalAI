package com.localai.runtime.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.localai.runtime.BuildConfig
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.SectionCard

private const val REPO_URL = "https://github.com/SecretArrow/LocalAI"
private const val ISSUES_URL = "https://github.com/SecretArrow/LocalAI/issues"

/** About screen: version, privacy statement, licenses, honest backend support, links. */
@Composable
fun AboutScreen(container: AppContainer, navController: NavHostController) {
    val context = LocalContext.current

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
            Text("About", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = null) {
                Text("LocalAI", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Run large language models entirely on your device — chat locally, " +
                        "and expose an OpenAI-compatible API on your own network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyValueRow("Version", BuildConfig.VERSION_NAME)
                KeyValueRow("Repository", "SecretArrow/LocalAI")
            }

            SectionCard(title = "Privacy") {
                Text(
                    "Everything runs on this device. Models, conversations and logs never leave " +
                        "your phone. Network access is used only to download models you choose and " +
                        "to serve the API server on your local network when you enable it. " +
                        "Nothing is uploaded, tracked or shared.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard(title = "Backend support (honest)") {
                Text(
                    "The UI never claims acceleration that is not actually used.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyValueRow("CPU", "Bundled — llama.cpp, real execution")
                KeyValueRow("Vulkan", "Not bundled — integration point documented")
                KeyValueRow("OpenCL", "Not bundled — integration point documented")
                KeyValueRow("NNAPI", "Not bundled — requires an ONNX/TFLite runtime")
                KeyValueRow("NPU", "No public NPU API exposed by Android devices")
            }

            SectionCard(title = "Licenses") {
                KeyValueRow("llama.cpp", "MIT")
                KeyValueRow("ZXing", "Apache-2.0")
                KeyValueRow("OkHttp", "Apache-2.0")
                KeyValueRow("Ktor", "Apache-2.0")
                KeyValueRow("kotlinx.serialization", "Apache-2.0")
                KeyValueRow("BouncyCastle", "MIT")
            }

            SectionCard(title = "Links") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Source code", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            REPO_URL,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Report an issue", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            ISSUES_URL,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
