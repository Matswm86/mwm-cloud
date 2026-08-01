package no.mwmai.mwmcloud

import android.content.Context
import no.mwmai.mwmcloud.data.ledger.UploadLedger
import no.mwmai.mwmcloud.data.media.MediaScanner
import no.mwmai.mwmcloud.data.media.SafScanner
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.WebDavTransport
import no.mwmai.mwmcloud.settings.AppSettings
import no.mwmai.mwmcloud.settings.BoxCredentials
import no.mwmai.mwmcloud.settings.CredentialStore

/**
 * Hand-built object graph. Small enough that a DI framework would cost more than
 * it saves, and explicit enough to read top to bottom.
 */
object Graph {

    @Volatile private var ledger: UploadLedger? = null
    @Volatile private var credentialStore: CredentialStore? = null
    @Volatile private var settings: AppSettings? = null
    @Volatile private var scanner: MediaScanner? = null
    @Volatile private var saf: SafScanner? = null

    fun ledger(context: Context): UploadLedger =
        ledger ?: synchronized(this) {
            ledger ?: UploadLedger(context.applicationContext).also { ledger = it }
        }

    fun credentialStore(context: Context): CredentialStore =
        credentialStore ?: synchronized(this) {
            credentialStore ?: CredentialStore(context.applicationContext).also { credentialStore = it }
        }

    fun settings(context: Context): AppSettings =
        settings ?: synchronized(this) {
            settings ?: AppSettings(context.applicationContext).also { settings = it }
        }

    fun mediaScanner(context: Context): MediaScanner =
        scanner ?: synchronized(this) {
            scanner ?: MediaScanner(context.applicationContext).also { scanner = it }
        }

    fun safScanner(context: Context): SafScanner =
        saf ?: synchronized(this) {
            saf ?: SafScanner(context.applicationContext).also { saf = it }
        }

    /** Not cached: credentials can change, and a stale transport would keep using the old ones. */
    fun transport(creds: BoxCredentials): Transport =
        WebDavTransport(creds.baseUrl, creds.username, creds.password)
}
