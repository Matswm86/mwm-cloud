package no.mwmai.mwmcloud.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The few Hetzner API calls automatic setup needs, and nothing more.
 *
 * Endpoints (verified 2026-08-15 against hcloud-python `storage_boxes/client.py`
 * and the hetzner.hcloud Ansible collection, both of which wrap this API):
 *
 *   GET  /storage_boxes                          -> { storage_boxes: [...] }
 *   POST /storage_boxes/{id}/subaccounts         -> { subaccount, action }
 *   GET  /storage_boxes/{id}/subaccounts/{sid}   -> { subaccount }
 *   GET  /storage_boxes/actions/{aid}            -> { action }
 *
 * The token is a Hetzner Console API token with read+write. It is held only for
 * the life of one [HetznerApi] instance and never written anywhere: after
 * provisioning, the app only needs the WebDAV sub-account it created.
 *
 * Every non-2xx response becomes a [HetznerApiException] whose [FailureKind]
 * tells the setup screen what to say: 401 is a bad token, network is network,
 * anything else is Hetzner's own message passed through.
 */
class HetznerApi(
    private val token: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun listStorageBoxes(): List<StorageBox> = io {
        get("storage_boxes").let { json.decodeFromString<StorageBoxList>(it).storageBoxes }
    }

    suspend fun createSubaccount(
        storageBoxId: Long,
        homeDirectory: String,
        password: String,
        description: String,
    ): CreateSubaccountResponse = io {
        val body = buildJsonObject {
            put("home_directory", homeDirectory)
            put("password", password)
            put("description", description)
            put(
                "access_settings",
                buildJsonObject {
                    put("webdav_enabled", true)
                    // A phone on mobile data is "external" by Hetzner's definition.
                    put("reachable_externally", true)
                    put("samba_enabled", false)
                    put("ssh_enabled", false)
                    put("readonly", false)
                },
            )
        }
        json.decodeFromString<CreateSubaccountResponse>(post("storage_boxes/$storageBoxId/subaccounts", body))
    }

    suspend fun getSubaccount(storageBoxId: Long, subaccountId: Long): Subaccount = io {
        json.decodeFromString<SubaccountWrapper>(get("storage_boxes/$storageBoxId/subaccounts/$subaccountId")).subaccount
    }

    suspend fun getAction(actionId: Long): Action = io {
        json.decodeFromString<ActionWrapper>(get("storage_boxes/actions/$actionId")).action
    }

    // ---- transport -----------------------------------------------------------

    private fun get(path: String): String = execute(request(path).get().build())

    private fun post(path: String, body: JsonObject): String =
        execute(request(path).post(body.toString().toRequestBody(JSON_MEDIA)).build())

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(base.newBuilder().addPathSegments(path).build())
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    private fun execute(request: Request): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw HetznerApiException(FailureKind.NETWORK, "Could not reach Hetzner: ${e.message}", e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return text
            val kind = when (r.code) {
                401, 403 -> FailureKind.AUTH
                404 -> FailureKind.NOT_FOUND
                else -> FailureKind.PROTOCOL
            }
            throw HetznerApiException(kind, "Hetzner ${r.code} on ${request.method} ${request.url.encodedPath}: ${errorMessage(text)}")
        }
    }

    /** Hetzner errors look like `{"error":{"code":"...","message":"..."}}`. Falls back to raw text. */
    private fun errorMessage(body: String): String = try {
        json.decodeFromString<ErrorWrapper>(body).error.message
    } catch (_: Exception) {
        body.take(200)
    }

    // ---- wire types ---------------------------------------------------------

    @Serializable
    data class StorageBox(
        val id: Long,
        val name: String = "",
        val server: String,
        val username: String,
        val status: String = "",
        val location: Location? = null,
        @SerialName("storage_box_type") val type: BoxType? = null,
        @SerialName("access_settings") val accessSettings: AccessSettings? = null,
    ) {
        /** e.g. "BX11 · Helsinki" so a user with two boxes can tell them apart. */
        val label: String
            get() = listOfNotNull(name.takeIf { it.isNotBlank() }, type?.description, location?.city)
                .distinct()
                .joinToString(" · ")
                .ifBlank { username }
    }

    @Serializable data class Location(val name: String = "", val city: String = "")

    @Serializable data class BoxType(val name: String = "", val description: String = "")

    @Serializable
    data class AccessSettings(
        @SerialName("webdav_enabled") val webdavEnabled: Boolean = false,
        @SerialName("reachable_externally") val reachableExternally: Boolean = false,
    )

    @Serializable
    data class Subaccount(
        val id: Long,
        val username: String = "",
        val server: String = "",
        @SerialName("home_directory") val homeDirectory: String = "",
        @SerialName("access_settings") val accessSettings: SubaccountAccessSettings? = null,
    )

    @Serializable
    data class SubaccountAccessSettings(
        @SerialName("webdav_enabled") val webdavEnabled: Boolean = false,
        @SerialName("reachable_externally") val reachableExternally: Boolean = false,
    )

    @Serializable
    data class Action(
        val id: Long,
        val status: String,
        val error: ActionError? = null,
    ) {
        val isRunning: Boolean get() = status == "running"
        val isSuccess: Boolean get() = status == "success"
    }

    @Serializable data class ActionError(val code: String = "", val message: String = "")

    @Serializable
    data class CreateSubaccountResponse(val subaccount: Subaccount, val action: Action)

    @Serializable private data class StorageBoxList(@SerialName("storage_boxes") val storageBoxes: List<StorageBox>)

    @Serializable private data class SubaccountWrapper(val subaccount: Subaccount)

    @Serializable private data class ActionWrapper(val action: Action)

    @Serializable private data class ErrorWrapper(val error: ErrorBody)

    @Serializable private data class ErrorBody(val code: String = "", val message: String = "")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.hetzner.com/v1"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Unknown keys are the norm: Hetzner adds fields, and we only read a few. */
        val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

class HetznerApiException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
