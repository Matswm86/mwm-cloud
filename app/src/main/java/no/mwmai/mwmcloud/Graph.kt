package no.mwmai.mwmcloud

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
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

    /** Absolute URL for a remote path, for the components that fetch outside [Transport]. */
    fun remoteUrl(creds: BoxCredentials, path: String): String =
        WebDavTransport.remoteUrl(creds.baseUrl, path).toString()

    /** The `Authorization` header value. UTF-8 Basic, from the one place that builds it. */
    fun authHeader(creds: BoxCredentials): String =
        WebDavTransport.basicAuth(creds.username, creds.password)

    @Volatile private var imageLoaderFor: String? = null

    @Volatile private var cachedImageLoader: ImageLoader? = null

    /**
     * Coil loader for the in-app viewer.
     *
     * The box has no thumbnail endpoint, so every tile in the grid is a full
     * photo downsampled on the device. Two things stop that from costing
     * gigabytes: the disk cache below, and the explicit `size()` the grid asks
     * for, which makes Coil decode small and keep the small copy.
     *
     * Auth is attached by an interceptor rather than per request, so no call
     * site can forget it.
     *
     * Cached per credential. Rebuilding the loader would throw away both caches,
     * and a re-entered screen would re-download the whole visible grid.
     */
    fun imageLoader(context: Context, creds: BoxCredentials): ImageLoader {
        val key = "${creds.baseUrl}|${creds.username}"
        cachedImageLoader?.let { if (imageLoaderFor == key) return it }
        return synchronized(this) {
            cachedImageLoader?.let { if (imageLoaderFor == key) return it }
            val app = context.applicationContext
            val header = authHeader(creds)
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", header)
                            .build(),
                    )
                }
                .build()
            ImageLoader.Builder(app)
                .okHttpClient(client)
                .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.20).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(app.cacheDir.resolve("viewer_images"))
                        .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                        .build()
                }
                .respectCacheHeaders(false)
                .build()
                .also { imageLoaderFor = key; cachedImageLoader = it }
        }
    }

    /**
     * 512 MB. Generous on purpose: scrolling back through a photo library is the
     * normal way this screen is used, and re-downloading the same month every
     * time would be both slow and, on mobile data, expensive.
     */
    private const val IMAGE_DISK_CACHE_BYTES = 512L * 1024 * 1024
}
