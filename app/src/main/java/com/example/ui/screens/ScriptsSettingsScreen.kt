package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.NyxoraSettingsState
import com.example.data.local.UserScriptItem
import com.example.data.local.UserScriptStorage
import java.util.UUID

/**
 * Category E: SCRIPTS Settings Section (Soul Browser style)
 * - JavaScript Enable / Disable
 * - Custom JavaScript / User Scripts Management
 * - Script Enable / Disable (master + per-script)
 * - Per-Website Script Rules (domain patterns & document start/end timing)
 * - Script Import / Export (never auto-executes imported scripts without explicit user approval)
 * - Script Execution Permissions
 */
@Composable
fun ScriptsSettingsSection(
    settings: NyxoraSettingsState,
    onUpdateSettings: ((NyxoraSettingsState) -> NyxoraSettingsState) -> Unit,
    onToast: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val scripts = remember(settings.userScriptsJson) {
        settings.parsedUserScripts
    }

    var editingScript by remember { mutableStateOf<UserScriptItem?>(null) }
    var showCreateOrEditDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonInput by remember { mutableStateOf("") }
    var importApproveImmediately by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_scripts_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SoulSettingsSectionHeader("JavaScript & Execution Permissions")

        SoulSettingsGroupCard {
            ReusableSettingSwitch(
                title = "Enable JavaScript",
                subtitle = "Allow websites and user scripts to run JavaScript in WebView",
                checked = settings.javascriptEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(javascriptEnabled = enabled) }
                },
                icon = Icons.Outlined.Code,
                testTag = "setting_scripts_javascript_toggle"
            )

            SoulSettingsSubtleDivider()

            ReusableSettingSwitch(
                title = "User Scripts Engine",
                subtitle = "Execute enabled custom JavaScript rules on matching domains",
                checked = settings.userScriptsEnabled,
                enabled = settings.javascriptEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(userScriptsEnabled = enabled) }
                },
                icon = Icons.Outlined.VerifiedUser,
                testTag = "setting_user_scripts_master_toggle"
            )

            SoulSettingsSubtleDivider()

            ReusableSettingSwitch(
                title = "Require Approval for Imported Scripts",
                subtitle = "Block imported scripts from running until explicitly reviewed and approved",
                checked = settings.userScriptsRequireApproval,
                onCheckedChange = { required ->
                    onUpdateSettings { it.copy(userScriptsRequireApproval = required) }
                },
                icon = Icons.Outlined.Security,
                testTag = "setting_user_scripts_approval_toggle"
            )
        }

        SoulSettingsSectionHeader("Custom User Scripts (${scripts.size})")

        SoulSettingsGroupCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manage Custom Scripts",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = SoulSettingsTextPrimary
                    )
                    Text(
                        text = "Create per-website or global (*) JavaScript enhancements",
                        style = MaterialTheme.typography.bodySmall,
                        color = SoulSettingsTextSecondary
                    )
                }
                Button(
                    onClick = {
                        editingScript = null
                        showCreateOrEditDialog = true
                    },
                    modifier = Modifier.testTag("scripts_add_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Script")
                }
            }

            if (scripts.isEmpty()) {
                SoulSettingsSubtleDivider()
                Text(
                    text = "No custom user scripts configured. Tap 'New Script' to add a per-site or global script, or import a JSON script bundle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoulSettingsTextSecondary,
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .testTag("scripts_empty_state_text")
                )
            } else {
                scripts.forEach { script ->
                    SoulSettingsSubtleDivider()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SoulSettingsSurfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scripts_item_${script.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = script.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = SoulSettingsTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Domain: ${script.domainPattern} • ${if (script.runAtDocumentEnd) "Document End" else "Document Start"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoulSettingsAccent
                                    )
                                }
                                Switch(
                                    checked = script.enabled && (!settings.userScriptsRequireApproval || script.approvedByUser),
                                    onCheckedChange = { checked ->
                                        val updatedList = scripts.map { item ->
                                            if (item.id == script.id) {
                                                item.copy(enabled = checked, approvedByUser = true)
                                            } else {
                                                item
                                            }
                                        }
                                        onUpdateSettings {
                                            it.copy(userScriptsJson = UserScriptStorage.serializeScripts(updatedList))
                                        }
                                    },
                                    modifier = Modifier.testTag("scripts_toggle_${script.id}")
                                )
                            }

                            Text(
                                text = script.code,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = SoulSettingsTextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!script.approvedByUser) {
                                    OutlinedButton(
                                        onClick = {
                                            val updatedList = scripts.map { item ->
                                                if (item.id == script.id) {
                                                    item.copy(approvedByUser = true, enabled = true)
                                                } else {
                                                    item
                                                }
                                            }
                                            onUpdateSettings {
                                                it.copy(userScriptsJson = UserScriptStorage.serializeScripts(updatedList))
                                            }
                                            onToast("Approved script '${script.name}'")
                                        },
                                        modifier = Modifier.testTag("scripts_approve_${script.id}")
                                    ) {
                                        Text("Approve Execution", fontSize = 11.sp)
                                    }
                                } else {
                                    Text(
                                        text = "Approved",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF34D399)
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = {
                                            editingScript = script
                                            showCreateOrEditDialog = true
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("scripts_edit_${script.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edit ${script.name}",
                                            tint = SoulSettingsTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val updatedList = scripts.filterNot { it.id == script.id }
                                            onUpdateSettings {
                                                it.copy(userScriptsJson = UserScriptStorage.serializeScripts(updatedList))
                                            }
                                            onToast("Removed script '${script.name}'")
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("scripts_delete_${script.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete ${script.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SoulSettingsSectionHeader("Script Import & Export")

        SoulSettingsGroupCard {
            ReusableSettingRow(
                title = "Import User Scripts (JSON)",
                subtitle = "Import scripts safely (disabled until you approve execution)",
                icon = Icons.Outlined.FileDownload,
                testTag = "scripts_import_button",
                onClick = {
                    importJsonInput = ""
                    importApproveImmediately = false
                    showImportDialog = true
                }
            )

            SoulSettingsSubtleDivider()

            ReusableSettingRow(
                title = "Export User Scripts (JSON)",
                subtitle = "Copy all ${scripts.size} configured scripts as JSON to clipboard",
                icon = Icons.Outlined.FileUpload,
                testTag = "scripts_export_button",
                onClick = {
                    val exported = UserScriptStorage.serializeScripts(scripts)
                    clipboardManager.setText(AnnotatedString(exported))
                    onToast("Exported ${scripts.size} user script(s) to clipboard")
                }
            )
        }
    }

    if (showCreateOrEditDialog) {
        val existing = editingScript
        var nameInput by remember(existing) { mutableStateOf(existing?.name ?: "") }
        var domainInput by remember(existing) { mutableStateOf(existing?.domainPattern ?: "*") }
        var codeInput by remember(existing) { mutableStateOf(existing?.code ?: "") }
        var runAtEnd by remember(existing) { mutableStateOf(existing?.runAtDocumentEnd ?: true) }

        AlertDialog(
            onDismissRequest = { showCreateOrEditDialog = false },
            containerColor = SoulSettingsSurface,
            titleContentColor = SoulSettingsTextPrimary,
            textContentColor = SoulSettingsTextSecondary,
            title = {
                Text(if (existing == null) "New User Script" else "Edit User Script")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Script Name") },
                        placeholder = { Text("Dark Reader Tweak") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("script_dialog_name_input")
                    )
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Website Rule (* or domain.com)") },
                        placeholder = { Text("* or example.com") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("script_dialog_domain_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !runAtEnd,
                            onClick = { runAtEnd = false },
                            label = { Text("Document Start") }
                        )
                        FilterChip(
                            selected = runAtEnd,
                            onClick = { runAtEnd = true },
                            label = { Text("Document End") }
                        )
                    }
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        label = { Text("JavaScript Code") },
                        placeholder = { Text("console.log('NYXORA UserScript active');") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("script_dialog_code_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedCode = codeInput.trim()
                        if (trimmedCode.isEmpty()) {
                            onToast("Script code cannot be empty")
                            return@Button
                        }
                        val finalItem = UserScriptItem(
                            id = existing?.id ?: "usr_${UUID.randomUUID().toString().take(8)}",
                            name = nameInput.trim().ifEmpty { "Custom Script" },
                            domainPattern = domainInput.trim().ifEmpty { "*" },
                            code = trimmedCode,
                            enabled = existing?.enabled ?: true,
                            runAtDocumentEnd = runAtEnd,
                            approvedByUser = true
                        )
                        val updatedList = if (existing == null) {
                            scripts + finalItem
                        } else {
                            scripts.map { if (it.id == existing.id) finalItem else it }
                        }
                        onUpdateSettings {
                            it.copy(userScriptsJson = UserScriptStorage.serializeScripts(updatedList))
                        }
                        showCreateOrEditDialog = false
                        onToast("Saved user script '${finalItem.name}'")
                    },
                    modifier = Modifier.testTag("script_dialog_save_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateOrEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = SoulSettingsSurface,
            titleContentColor = SoulSettingsTextPrimary,
            textContentColor = SoulSettingsTextSecondary,
            title = { Text("Import User Scripts (JSON)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "For security, imported scripts are disabled by default until you explicitly approve them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SoulSettingsTextSecondary
                    )
                    OutlinedTextField(
                        value = importJsonInput,
                        onValueChange = { importJsonInput = it },
                        placeholder = { Text("[{\"name\":\"My Script\",\"domainPattern\":\"*\",\"code\":\"...\"}]") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("script_import_json_input")
                    )
                    ReusableSettingSwitch(
                        title = "Approve imported scripts now",
                        subtitle = "Only enable if you trust the author of this script bundle",
                        checked = importApproveImmediately,
                        onCheckedChange = { importApproveImmediately = it },
                        testTag = "script_import_approve_toggle"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val imported = UserScriptStorage.parseImportedScripts(
                            rawJson = importJsonInput,
                            userExplicitlyApproved = importApproveImmediately
                        )
                        if (imported.isEmpty()) {
                            onToast("No valid scripts found in JSON")
                        } else {
                            val merged = (scripts + imported).distinctBy { it.id }
                            onUpdateSettings {
                                it.copy(userScriptsJson = UserScriptStorage.serializeScripts(merged))
                            }
                            showImportDialog = false
                            val statusMsg = if (importApproveImmediately) {
                                "Imported and approved ${imported.size} script(s)"
                            } else {
                                "Imported ${imported.size} script(s) (pending your approval)"
                            }
                            onToast(statusMsg)
                        }
                    },
                    modifier = Modifier.testTag("script_import_confirm_button")
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
