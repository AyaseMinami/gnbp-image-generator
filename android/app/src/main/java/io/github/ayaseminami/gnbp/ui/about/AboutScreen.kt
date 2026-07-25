package io.github.ayaseminami.gnbp.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenDestination: (AboutDestination) -> Unit,
    appInfo: AboutAppInfo = AboutAppInfo.current(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_back),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = stringResource(R.string.about_app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.about_author, appInfo.author),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.about_version,
                    appInfo.versionName,
                    appInfo.versionCode,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.about_project_title),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(
            onClick = { onOpenDestination(AboutDestination.Repository) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Source, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_source_repository))
        }
        OutlinedButton(
            onClick = { onOpenDestination(AboutDestination.License) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Description, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_license))
        }
        Button(
            onClick = { onOpenDestination(AboutDestination.Releases) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_open_releases))
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.about_privacy_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.about_privacy_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
