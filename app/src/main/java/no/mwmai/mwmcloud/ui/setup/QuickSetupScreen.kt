package no.mwmai.mwmcloud.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.data.provision.HetznerProvisioner
import no.mwmai.mwmcloud.data.provision.HetznerProvisioner.ProvisionException
import no.mwmai.mwmcloud.data.provision.HetznerProvisioner.Reason
import no.mwmai.mwmcloud.data.provision.HetznerProvisioner.Step
import no.mwmai.mwmcloud.net.HetznerApi
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * Automatic setup: paste one Hetzner API token, get a working backup target.
 *
 * The user still orders the Storage Box on hetzner.com (account, card, one
 * product); that part cannot be done from a phone app. Everything after that
 * happens here: sub-account, WebDAV, password, connection test. The token is
 * used once and dropped; the box's own login never reaches the phone.
 *
 * Progress is shown step by step from [HetznerProvisioner], and each failure
 * maps to a sentence that says what to do next, same discipline as [SetupScreen].
 */
@Composable
fun QuickSetupScreen(
    onConnected: () -> Unit,
    onManualInstead: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<Step?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var boxes by remember { mutableStateOf<List<HetznerApi.StorageBox>>(emptyList()) }
    var chosenBox by remember { mutableStateOf<Long?>(null) }

    fun run() {
        busy = true
        error = null
        step = null
        scope.launch {
            val api = HetznerApi(token.trim())
            val provisioner = HetznerProvisioner(api, transportFor = { Graph.transport(it) })
            val result = runCatching {
                provisioner.provision(
                    chosenBoxId = chosenBox,
                    listener = object : HetznerProvisioner.ProvisionListener {
                        override fun onStep(s: Step) { step = s }
                    },
                )
            }
            result
                .onSuccess { creds ->
                    Graph.credentialStore(context).save(creds)
                    Graph.settings(context).markSetupComplete()
                    token = ""
                    busy = false
                    onConnected()
                }
                .onFailure { e ->
                    busy = false
                    if (e is ProvisionException && e.reason == Reason.CHOOSE_BOX) {
                        boxes = e.boxes
                        chosenBox = e.boxes.firstOrNull()?.id
                        error = context.getString(R.string.quick_choose_box)
                    } else if (e is ProvisionException && e.reason == Reason.WEBDAV_NOT_UP && e.creds != null) {
                        // The account is real; keep the credentials so a retry from
                        // the home screen or manual setup does not lose them.
                        Graph.credentialStore(context).save(e.creds)
                        error = context.getString(R.string.quick_err_webdav_not_up)
                    } else {
                        error = describe(e, context)
                    }
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.quick_title), style = MaterialTheme.typography.headlineLarge, color = MwmColors.Text)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.quick_body), style = MaterialTheme.typography.bodyLarge, color = MwmColors.Muted)
        Spacer(Modifier.height(20.dp))

        StepLine("1", stringResource(R.string.quick_step_order))
        StepLine("2", stringResource(R.string.quick_step_token))
        StepLine("3", stringResource(R.string.quick_step_paste))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.quick_open_hetzner),
            style = MaterialTheme.typography.labelLarge,
            color = MwmColors.Action,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HETZNER_STORAGE_BOX_URL)))
            },
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.quick_token), style = MaterialTheme.typography.labelMedium) },
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodyLarge,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedIndicatorColor = MwmColors.Action,
                unfocusedIndicatorColor = MwmColors.Border,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.quick_token_note), style = MaterialTheme.typography.labelMedium, color = MwmColors.Muted)

        if (boxes.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.quick_which_box), style = MaterialTheme.typography.labelLarge, color = MwmColors.Text)
            boxes.forEach { box ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { chosenBox = box.id },
                ) {
                    RadioButton(selected = chosenBox == box.id, onClick = { chosenBox = box.id }, enabled = !busy)
                    Text("${box.label} (${box.username})", style = MaterialTheme.typography.bodyMedium, color = MwmColors.Text)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        step?.let {
            Text(stepText(it), style = MaterialTheme.typography.bodyMedium, color = MwmColors.Text)
            Spacer(Modifier.height(12.dp))
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MwmColors.Attention)
            Spacer(Modifier.height(12.dp))
        }

        PrimaryButton(
            text = stringResource(R.string.quick_go),
            enabled = token.isNotBlank() && !busy,
            busy = busy,
            onClick = { run() },
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.quick_manual_instead), onManualInstead, enabled = !busy)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.back), onBack, enabled = !busy)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepLine(n: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$n.  ", style = MaterialTheme.typography.bodyLarge, color = MwmColors.Action)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MwmColors.Text)
    }
}

@Composable
private fun stepText(step: Step): String = when (step) {
    Step.LISTING_BOXES -> stringResource(R.string.quick_step_listing)
    Step.CREATING_ACCOUNT -> stringResource(R.string.quick_step_creating)
    Step.WAITING_FOR_HETZNER -> stringResource(R.string.quick_step_waiting_hetzner)
    Step.WAITING_FOR_WEBDAV -> stringResource(R.string.quick_step_waiting_webdav)
    Step.DONE -> stringResource(R.string.quick_step_done)
}

private fun describe(e: Throwable, context: android.content.Context): String {
    val reason = (e as? ProvisionException)?.reason
    return when (reason) {
        Reason.BAD_TOKEN -> context.getString(R.string.quick_err_token)
        Reason.NETWORK -> context.getString(R.string.err_network)
        Reason.NO_BOX -> context.getString(R.string.quick_err_no_box)
        Reason.BOX_NOT_READY -> context.getString(R.string.quick_err_box_not_ready)
        Reason.TIMEOUT -> context.getString(R.string.quick_err_timeout)
        Reason.HETZNER_ERROR -> context.getString(R.string.quick_err_hetzner, e.message ?: "")
        else -> context.getString(R.string.err_generic)
    }
}

private const val HETZNER_STORAGE_BOX_URL = "https://www.hetzner.com/storage/storage-box/"
