package no.mwmai.mwmcloud.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.mwmai.mwmcloud.Graph
import no.mwmai.mwmcloud.R
import no.mwmai.mwmcloud.net.FailureKind
import no.mwmai.mwmcloud.net.TransportException
import no.mwmai.mwmcloud.settings.BoxCredentials
import no.mwmai.mwmcloud.ui.PrimaryButton
import no.mwmai.mwmcloud.ui.SecondaryButton
import no.mwmai.mwmcloud.ui.theme.MwmColors
import no.mwmai.mwmcloud.ui.theme.MwmDimens

/**
 * Manual connection setup.
 *
 * "Test tilkobling" performs a real request against the box and reports what
 * actually happened. It never reports success optimistically, and it never
 * reduces a specific failure to "something went wrong" when it knows better:
 * a wrong password and an unreachable host need different actions from the user.
 */
@Composable
fun SetupScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val canSubmit = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MwmColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(MwmDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MwmColors.Text,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MwmColors.Muted,
        )
        Spacer(Modifier.height(28.dp))

        Field(host, { host = it }, stringResource(R.string.setup_host), "u123456.your-storagebox.de")
        Spacer(Modifier.height(16.dp))
        Field(user, { user = it }, stringResource(R.string.setup_user), "u123456")
        Spacer(Modifier.height(16.dp))
        Field(
            value = pass,
            onValueChange = { pass = it },
            label = stringResource(R.string.setup_pass),
            placeholder = "",
            isPassword = true,
        )

        Spacer(Modifier.height(28.dp))

        message?.let { (text, isError) ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MwmColors.Attention else MwmColors.Safe,
            )
            Spacer(Modifier.height(16.dp))
        }

        PrimaryButton(
            text = stringResource(R.string.setup_test),
            enabled = canSubmit,
            busy = busy,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    val creds = BoxCredentials(host.trim(), user.trim(), pass)
                    val result = runCatching { Graph.transport(creds).testConnection() }
                    busy = false
                    result
                        .onSuccess {
                            Graph.credentialStore(context).save(creds)
                            Graph.settings(context).markSetupComplete()
                            onConnected()
                        }
                        .onFailure { e ->
                            message = describe(e, context) to true
                        }
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.back), onBack)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Turns a failure into something the user can act on. Vague errors are how people
 * end up believing their password is wrong when the wifi is off.
 */
private fun describe(e: Throwable, context: android.content.Context): String = when {
    e is TransportException && e.kind == FailureKind.AUTH ->
        context.getString(R.string.err_auth)
    e is TransportException && e.kind == FailureKind.NETWORK ->
        context.getString(R.string.err_network)
    e is TransportException && e.kind == FailureKind.NOT_FOUND ->
        context.getString(R.string.err_not_found)
    else -> context.getString(R.string.err_generic)
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MwmColors.Muted)
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Uri,
            autoCorrectEnabled = false,
        ),
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.White,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
            focusedIndicatorColor = MwmColors.Action,
            unfocusedIndicatorColor = MwmColors.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
