package io.github.ayaseminami.gnbp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.prompt.PromptPreset
import io.github.ayaseminami.gnbp.provider.transport.ProfileId

internal const val SETTINGS_ADD_PROFILE_TEST_TAG = "settings-add-profile"
internal const val SETTINGS_SAVE_PROFILE_TEST_TAG = "settings-save-profile"

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    when {
        state.profileEditor != null -> ProfileEditor(
            editor = state.profileEditor,
            isBusy = state.isBusy,
            actions = actions,
        )
        state.promptEditor != null -> PromptEditor(
            editor = state.promptEditor,
            isBusy = state.isBusy,
            actions = actions,
        )
        else -> SettingsHome(state = state, actions = actions)
    }
}

@Composable
private fun SettingsHome(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var deleteProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePromptId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_profiles_title),
            actionLabel = stringResource(R.string.add_profile),
            onAction = actions.onNewProfile,
            actionTestTag = SETTINGS_ADD_PROFILE_TEST_TAG,
        )
        if (state.profiles.isEmpty()) {
            Text(
                stringResource(R.string.settings_profiles_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    onEdit = { actions.onEditProfile(profile.id) },
                    onDelete = { deleteProfileId = profile.id.value },
                )
            }
        }

        HorizontalDivider()
        SettingsSectionHeader(
            title = stringResource(R.string.settings_prompts_title),
            actionLabel = stringResource(R.string.add_prompt),
            onAction = actions.onNewPrompt,
        )
        if (state.prompts.isEmpty()) {
            Text(
                stringResource(R.string.settings_prompts_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.prompts.forEach { prompt ->
                PromptRow(
                    prompt = prompt,
                    onEdit = { actions.onEditPrompt(prompt.id.value) },
                    onDelete = { deletePromptId = prompt.id.value },
                )
            }
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_behavior_title),
            style = MaterialTheme.typography.titleMedium,
        )
        SettingToggle(
            title = stringResource(R.string.setting_show_preview),
            summary = stringResource(R.string.setting_show_preview_summary),
            checked = state.appSettings.showPreview,
            onCheckedChange = actions.onUpdateShowPreview,
        )
        SettingToggle(
            title = stringResource(R.string.setting_completion_notifications),
            summary = stringResource(R.string.setting_completion_notifications_summary),
            checked = state.appSettings.completionNotifications,
            onCheckedChange = actions.onUpdateCompletionNotifications,
        )
        SettingToggle(
            title = stringResource(R.string.setting_notification_sound),
            summary = stringResource(R.string.setting_notification_sound_summary),
            checked = state.appSettings.soundNotification,
            enabled = state.appSettings.completionNotifications,
            onCheckedChange = actions.onUpdateSoundNotification,
        )
        ConcurrencySetting(
            value = state.appSettings.maxConcurrency,
            onValueChange = actions.onUpdateMaxConcurrency,
        )
        Spacer(Modifier.height(16.dp))
    }

    deleteProfileId?.let { rawId ->
        DeleteDialog(
            title = stringResource(R.string.delete_profile_title),
            message = stringResource(R.string.delete_profile_message),
            onConfirm = {
                deleteProfileId = null
                actions.onDeleteProfile(ProfileId(rawId))
            },
            onDismiss = { deleteProfileId = null },
        )
    }
    deletePromptId?.let { rawId ->
        DeleteDialog(
            title = stringResource(R.string.delete_prompt_title),
            message = stringResource(R.string.delete_prompt_message),
            onConfirm = {
                deletePromptId = null
                actions.onDeletePrompt(rawId)
            },
            onDismiss = { deletePromptId = null },
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onAction,
            modifier = if (actionTestTag == null) Modifier else Modifier.testTag(actionTestTag),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileSummary,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile.isUnsafe) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.profile_unsafe),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (profile.providerKind == ProviderKind.Gemini) {
                            R.string.provider_gemini
                        } else {
                            R.string.provider_openai_compatible
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    profile.model,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_profile))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_profile))
            }
        }
    }
}

@Composable
private fun PromptRow(
    prompt: PromptPreset,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(prompt.name, style = MaterialTheme.typography.titleSmall)
            Text(
                prompt.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_prompt))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_prompt))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    editor: ProfileEditorState,
    isBusy: Boolean,
    actions: SettingsActions,
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(if (editor.isNew) R.string.new_profile_title else R.string.edit_profile_title),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = editor.name,
            onValueChange = { actions.onUpdateProfileText(ProfileTextField.Name, it) },
            label = { Text(stringResource(R.string.profile_name_label)) },
            enabled = !isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.provider_type_label), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ProviderKind.entries.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = editor.providerKind == kind,
                    onClick = { actions.onUpdateProviderKind(kind) },
                    shape = SegmentedButtonDefaults.itemShape(index, ProviderKind.entries.size),
                    enabled = !isBusy,
                ) {
                    Text(
                        stringResource(
                            if (kind == ProviderKind.Gemini) {
                                R.string.provider_gemini
                            } else {
                                R.string.provider_openai_short
                            },
                        ),
                    )
                }
            }
        }
        OutlinedTextField(
            value = editor.endpoint,
            onValueChange = { actions.onUpdateProfileText(ProfileTextField.Endpoint, it) },
            label = { Text(stringResource(R.string.endpoint_label)) },
            supportingText = { Text(stringResource(R.string.endpoint_summary)) },
            enabled = !isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editor.model,
            onValueChange = { actions.onUpdateProfileText(ProfileTextField.Model, it) },
            label = { Text(stringResource(R.string.model_label)) },
            enabled = !isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editor.apiKeyReplacement,
            onValueChange = { actions.onUpdateProfileText(ProfileTextField.ApiKey, it) },
            label = { Text(stringResource(R.string.api_key_label)) },
            placeholder = {
                if (!editor.isNew) Text(stringResource(R.string.api_key_keep_existing))
            },
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (apiKeyVisible) R.string.hide_api_key else R.string.show_api_key,
                        ),
                    )
                }
            },
            enabled = !isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecurityModePicker(
            selected = editor.transportChoice,
            enabled = !isBusy,
            onSelect = actions.onUpdateTransportChoice,
        )
        SecurityOptions(editor = editor, isBusy = isBusy, actions = actions)
        SettingToggle(
            title = stringResource(R.string.allow_lan_title),
            summary = stringResource(R.string.allow_lan_summary),
            checked = editor.allowLan,
            enabled = !isBusy,
            onCheckedChange = actions.onUpdateAllowLan,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = actions.onCancelProfileEditor, enabled = !isBusy) {
                Text(stringResource(R.string.cancel_edit))
            }
            Button(
                onClick = actions.onSaveProfile,
                enabled = !isBusy,
                modifier = Modifier.testTag(SETTINGS_SAVE_PROFILE_TEST_TAG),
            ) {
                Text(stringResource(R.string.save_profile))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SecurityOptions(
    editor: ProfileEditorState,
    isBusy: Boolean,
    actions: SettingsActions,
) {
    when (editor.transportChoice) {
        ProfileTransportChoice.VerifiedTls -> Text(
            stringResource(R.string.security_verified_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        ProfileTransportChoice.CustomCaTls -> {
            CertificateControls(editor, isBusy, actions)
            OutlinedTextField(
                value = editor.spkiPins,
                onValueChange = { actions.onUpdateProfileText(ProfileTextField.SpkiPins, it) },
                label = { Text(stringResource(R.string.spki_pins_label)) },
                supportingText = { Text(stringResource(R.string.spki_pins_summary)) },
                minLines = 2,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ProfileTransportChoice.PinnedServerCertificateTls -> {
            CertificateControls(editor, isBusy, actions)
            SettingToggle(
                title = stringResource(R.string.allow_hostname_mismatch_title),
                summary = stringResource(R.string.allow_hostname_mismatch_summary),
                checked = editor.allowHostnameMismatch,
                enabled = !isBusy && editor.importedCertificateCount == 1,
                onCheckedChange = actions.onUpdateAllowHostnameMismatch,
            )
        }
        ProfileTransportChoice.UnsafeTrustAllTls,
        ProfileTransportChoice.CleartextHttp,
        -> {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (editor.transportChoice == ProfileTransportChoice.CleartextHttp) {
                            R.string.security_cleartext_warning
                        } else {
                            R.string.security_trust_all_warning
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = editor.unsafeAcknowledged,
                    onCheckedChange = actions.onUpdateUnsafeAcknowledgement,
                    enabled = !isBusy,
                )
                Text(stringResource(R.string.security_unsafe_acknowledgement))
            }
        }
    }
}

@Composable
private fun CertificateControls(
    editor: ProfileEditorState,
    isBusy: Boolean,
    actions: SettingsActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = actions.onImportCertificate, enabled = !isBusy) {
            Text(stringResource(R.string.import_certificate))
        }
        Text(
            pluralStringResource(
                R.plurals.imported_certificate_count,
                editor.importedCertificateCount,
                editor.importedCertificateCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (editor.importedCertificateCount > 0) {
            TextButton(onClick = actions.onClearCertificates, enabled = !isBusy) {
                Text(stringResource(R.string.clear_certificates))
            }
        }
    }
}

@Composable
private fun SecurityModePicker(
    selected: ProfileTransportChoice,
    enabled: Boolean,
    onSelect: (ProfileTransportChoice) -> Unit,
) {
    val options = ProfileTransportChoice.entries
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.security_mode_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(securityChoiceLabel(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(securityChoiceLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun securityChoiceLabel(choice: ProfileTransportChoice): String = stringResource(
    when (choice) {
        ProfileTransportChoice.VerifiedTls -> R.string.security_verified
        ProfileTransportChoice.CustomCaTls -> R.string.security_custom_ca
        ProfileTransportChoice.PinnedServerCertificateTls -> R.string.security_pinned_certificate
        ProfileTransportChoice.UnsafeTrustAllTls -> R.string.security_trust_all
        ProfileTransportChoice.CleartextHttp -> R.string.security_cleartext
    },
)

@Composable
private fun PromptEditor(
    editor: PromptEditorState,
    isBusy: Boolean,
    actions: SettingsActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(if (editor.isNew) R.string.new_prompt_title else R.string.edit_prompt_title),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = editor.name,
            onValueChange = actions.onUpdatePromptName,
            label = { Text(stringResource(R.string.prompt_name_label)) },
            enabled = !isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editor.content,
            onValueChange = actions.onUpdatePromptContent,
            label = { Text(stringResource(R.string.prompt_content_label)) },
            enabled = !isBusy,
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = actions.onCancelPromptEditor, enabled = !isBusy) {
                Text(stringResource(R.string.cancel_edit))
            }
            Button(onClick = actions.onSavePrompt, enabled = !isBusy) {
                Text(stringResource(R.string.save_prompt))
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ConcurrencySetting(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.setting_concurrency), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.setting_concurrency_summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = { onValueChange(value - 1) }, enabled = value > 1) {
            Icon(
                Icons.Default.Remove,
                contentDescription = stringResource(R.string.decrease_concurrency),
            )
        }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onValueChange(value + 1) }, enabled = value < 2) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.increase_concurrency),
            )
        }
    }
}

@Composable
private fun DeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss_dialog)) }
        },
    )
}
