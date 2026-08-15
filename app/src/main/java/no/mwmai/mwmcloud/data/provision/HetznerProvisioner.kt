package no.mwmai.mwmcloud.data.provision

import java.security.SecureRandom
import kotlinx.coroutines.delay
import no.mwmai.mwmcloud.net.FailureKind
import no.mwmai.mwmcloud.net.HetznerApi
import no.mwmai.mwmcloud.net.HetznerApiException
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.TransportException
import no.mwmai.mwmcloud.settings.BoxCredentials

/**
 * Turns "I have a Hetzner account" into working WebDAV credentials, with no
 * Hetzner console visit beyond copying one API token.
 *
 * Steps, each reported through [ProvisionListener] so the screen can show
 * real progress instead of a spinner:
 *
 *  1. list the account's Storage Boxes (0 -> [ProvisionException] NO_BOX; >1
 *     without a choice -> CHOOSE_BOX with the list, so the UI can ask);
 *  2. create a WebDAV-only sub-account with its own generated password and
 *     home directory [HOME_DIRECTORY]. A sub-account, not the main login,
 *     because the phone should never hold the credential that owns the box,
 *     and because a reinstall then reseeds from the same folder;
 *  3. wait for Hetzner's action to finish;
 *  4. wait for WebDAV to answer, which Hetzner documents as "a few minutes"
 *     after activation. We poll PROPFIND with backoff up to [WEBDAV_WAIT_MAX_MS]
 *     rather than declaring success on the API's word alone.
 *
 * The API token is used only inside this call and is not persisted.
 */
class HetznerProvisioner(
    private val api: HetznerApi,
    private val transportFor: (BoxCredentials) -> Transport,
    private val random: SecureRandom = SecureRandom(),
    private val clockDelay: suspend (Long) -> Unit = { delay(it) },
) {

    interface ProvisionListener {
        fun onStep(step: Step)
    }

    enum class Step { LISTING_BOXES, CREATING_ACCOUNT, WAITING_FOR_HETZNER, WAITING_FOR_WEBDAV, DONE }

    /**
     * @param chosenBoxId which box to use when the account has several. Null
     *   means "use the only one, or ask me".
     */
    suspend fun provision(
        chosenBoxId: Long? = null,
        listener: ProvisionListener? = null,
    ): BoxCredentials {
        listener?.onStep(Step.LISTING_BOXES)
        val boxes = api.listStorageBoxes()
        val box = when {
            boxes.isEmpty() -> throw ProvisionException(Reason.NO_BOX, "This Hetzner account has no Storage Box yet.")
            chosenBoxId != null -> boxes.firstOrNull { it.id == chosenBoxId }
                ?: throw ProvisionException(Reason.NO_BOX, "Storage Box $chosenBoxId not found in this account.")
            boxes.size == 1 -> boxes.single()
            else -> throw ProvisionException(Reason.CHOOSE_BOX, "More than one Storage Box.", boxes = boxes)
        }
        if (box.status.isNotEmpty() && box.status != "active") {
            throw ProvisionException(Reason.BOX_NOT_READY, "Storage Box ${box.username} is ${box.status}, not active yet.")
        }

        listener?.onStep(Step.CREATING_ACCOUNT)
        val password = generatePassword(random)
        val created = try {
            api.createSubaccount(
                storageBoxId = box.id,
                homeDirectory = HOME_DIRECTORY,
                password = password,
                description = "MWM Cloud (phone backup)",
            )
        } catch (e: HetznerApiException) {
            throw ProvisionException(reasonFor(e), e.message ?: "Could not create sub-account.", e)
        }

        listener?.onStep(Step.WAITING_FOR_HETZNER)
        awaitAction(created.action.id)
        val sub = api.getSubaccount(box.id, created.subaccount.id)
        val username = sub.username.ifBlank { created.subaccount.username }
        if (username.isBlank()) throw ProvisionException(Reason.HETZNER_ERROR, "Hetzner returned a sub-account without a username.")
        val host = webdavHost(mainUsername = box.username, mainServer = box.server, subUsername = username)
        val creds = BoxCredentials(host = host, username = username, password = password)

        listener?.onStep(Step.WAITING_FOR_WEBDAV)
        awaitWebDav(creds)
        listener?.onStep(Step.DONE)
        return creds
    }

    private suspend fun awaitAction(actionId: Long) {
        var waited = 0L
        var interval = 1_000L
        while (true) {
            val action = api.getAction(actionId)
            when {
                action.isSuccess -> return
                action.isRunning -> Unit
                else -> throw ProvisionException(
                    Reason.HETZNER_ERROR,
                    "Hetzner could not create the sub-account: ${action.error?.message ?: action.status}",
                )
            }
            if (waited >= ACTION_WAIT_MAX_MS) {
                throw ProvisionException(Reason.TIMEOUT, "Hetzner is still working after ${ACTION_WAIT_MAX_MS / 1000}s.")
            }
            clockDelay(interval)
            waited += interval
            interval = (interval * 2).coerceAtMost(5_000L)
        }
    }

    /**
     * WebDAV on a fresh sub-account comes up asynchronously. 401 in this window
     * means "not yet", not "wrong password": we generated the password ourselves
     * and Hetzner accepted it, so only a timeout is treated as failure.
     */
    private suspend fun awaitWebDav(creds: BoxCredentials) {
        val transport = transportFor(creds)
        var waited = 0L
        var interval = 3_000L
        var last: Throwable? = null
        while (waited < WEBDAV_WAIT_MAX_MS) {
            try {
                transport.testConnection()
                return
            } catch (e: TransportException) {
                last = e
                // Anything the box says (401/404/5xx) or a connect failure while
                // DNS for the new host propagates: keep waiting.
            }
            clockDelay(interval)
            waited += interval
            interval = (interval * 3 / 2).coerceAtMost(15_000L)
        }
        throw ProvisionException(
            Reason.WEBDAV_NOT_UP,
            "The account exists but WebDAV did not answer within ${WEBDAV_WAIT_MAX_MS / 60_000} minutes.",
            last,
            creds = creds,
        )
    }

    private fun reasonFor(e: HetznerApiException): Reason = when (e.kind) {
        FailureKind.AUTH -> Reason.BAD_TOKEN
        FailureKind.NETWORK -> Reason.NETWORK
        else -> Reason.HETZNER_ERROR
    }

    enum class Reason { BAD_TOKEN, NETWORK, NO_BOX, CHOOSE_BOX, BOX_NOT_READY, HETZNER_ERROR, TIMEOUT, WEBDAV_NOT_UP }

    class ProvisionException(
        val reason: Reason,
        message: String,
        cause: Throwable? = null,
        /** Filled for [Reason.CHOOSE_BOX] so the UI can present the choice. */
        val boxes: List<HetznerApi.StorageBox> = emptyList(),
        /**
         * Filled for [Reason.WEBDAV_NOT_UP]: the sub-account is real and these
         * credentials will work once Hetzner finishes. The UI offers to save them
         * and retry from the manual screen instead of throwing them away.
         */
        val creds: BoxCredentials? = null,
    ) : Exception(message, cause)

    companion object {
        /** Sub-account home directory: fixed, so a reinstall reseeds against the same files. */
        const val HOME_DIRECTORY = "mwmcloud"

        const val ACTION_WAIT_MAX_MS = 120_000L
        const val WEBDAV_WAIT_MAX_MS = 5 * 60_000L

        /**
         * 28 chars from an unambiguous alphanumeric alphabet, ~160 bits. No
         * punctuation on purpose: it is never typed by a human, and it keeps
         * every shell/URL/Basic-auth encoding path trivially safe.
         */
        fun generatePassword(random: SecureRandom = SecureRandom(), length: Int = 28): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        /**
         * WebDAV host for a sub-account. Hetzner documents
         * `https://u#####-sub#.your-storagebox.de` (docs.hetzner.com, access-webdav).
         * Derived from the main box's `server` so a non-default domain survives;
         * falls back to the documented default when `server` is not username-prefixed.
         */
        fun webdavHost(mainUsername: String, mainServer: String, subUsername: String): String {
            val server = mainServer.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            return if (mainUsername.isNotBlank() && server.startsWith("$mainUsername.")) {
                subUsername + server.removePrefix(mainUsername)
            } else {
                "$subUsername.your-storagebox.de"
            }
        }
    }
}
