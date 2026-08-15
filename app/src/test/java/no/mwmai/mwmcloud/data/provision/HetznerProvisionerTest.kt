package no.mwmai.mwmcloud.data.provision

import java.io.InputStream
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import no.mwmai.mwmcloud.data.provision.HetznerProvisioner.Reason
import no.mwmai.mwmcloud.data.provision.HetznerProvisioner.Step
import no.mwmai.mwmcloud.net.Content
import no.mwmai.mwmcloud.net.FailureKind
import no.mwmai.mwmcloud.net.HetznerApi
import no.mwmai.mwmcloud.net.RemoteEntry
import no.mwmai.mwmcloud.net.Transport
import no.mwmai.mwmcloud.net.TransportException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HetznerProvisionerTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HetznerApi
    private val delays = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = HetznerApi(token = "t", baseUrl = server.url("/v1").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- pure helpers -------------------------------------------------------

    @Test
    fun `password is 28 chars from the safe alphabet`() {
        repeat(20) {
            val p = HetznerProvisioner.generatePassword(SecureRandom())
            assertEquals(28, p.length)
            assertTrue(p, p.all { it.isLetterOrDigit() && it !in "0O1lI" })
        }
    }

    @Test
    fun `webdav host swaps the main username for the sub-account username`() {
        assertEquals(
            "u123456-sub1.your-storagebox.de",
            HetznerProvisioner.webdavHost("u123456", "u123456.your-storagebox.de", "u123456-sub1"),
        )
        // Non-default server domain survives.
        assertEquals(
            "u9-sub2.example.net",
            HetznerProvisioner.webdavHost("u9", "https://u9.example.net/", "u9-sub2"),
        )
        // Server not prefixed by username: fall back to documented default.
        assertEquals(
            "u123456-sub1.your-storagebox.de",
            HetznerProvisioner.webdavHost("u123456", "fsn1-bx11.hetzner.com", "u123456-sub1"),
        )
    }

    // ---- end to end against a mock Hetzner + fake WebDAV -----------------------

    private fun enqueueHappyPath(actionRunsFor: Int = 1) {
        server.enqueue(MockResponse().setBody(ONE_BOX))
        server.enqueue(MockResponse().setResponseCode(201).setBody(CREATED))
        repeat(actionRunsFor) { server.enqueue(MockResponse().setBody("""{"action":{"id":99,"status":"running"}}""")) }
        server.enqueue(MockResponse().setBody("""{"action":{"id":99,"status":"success"}}"""))
        server.enqueue(MockResponse().setBody("""{"subaccount":{"id":7,"username":"u123456-sub1","server":"u123456-sub1.your-storagebox.de","home_directory":"mwmcloud"}}"""))
    }

    @Test
    fun `happy path yields sub-account creds and reports every step`() = runBlocking {
        enqueueHappyPath(actionRunsFor = 2)
        var attempts = 0
        val steps = mutableListOf<Step>()
        val p = HetznerProvisioner(
            api,
            transportFor = { creds ->
                fakeTransport { if (++attempts < 3) throw TransportException(FailureKind.AUTH, "not yet") }
            },
            clockDelay = { delays += it },
        )
        val creds = p.provision(listener = object : HetznerProvisioner.ProvisionListener {
            override fun onStep(step: Step) { steps += step }
        })
        assertEquals("u123456-sub1", creds.username)
        assertEquals("u123456-sub1.your-storagebox.de", creds.host)
        assertEquals("https://u123456-sub1.your-storagebox.de", creds.baseUrl)
        assertEquals(28, creds.password.length)
        assertEquals(3, attempts) // WebDAV 401s twice while activating, then answers.
        assertEquals(
            listOf(Step.LISTING_BOXES, Step.CREATING_ACCOUNT, Step.WAITING_FOR_HETZNER, Step.WAITING_FOR_WEBDAV, Step.DONE),
            steps,
        )
        // Password sent to Hetzner is the one we return.
        server.takeRequest() // list
        val create = server.takeRequest()
        assertTrue(create.body.readUtf8().contains("\"password\":\"${creds.password}\""))
    }

    @Test
    fun `no storage box is a NO_BOX failure before anything is created`() {
        server.enqueue(MockResponse().setBody("""{"storage_boxes":[]}"""))
        val p = HetznerProvisioner(api, transportFor = { fakeTransport {} }, clockDelay = {})
        val e = assertThrows(HetznerProvisioner.ProvisionException::class.java) { runBlocking { p.provision() } }
        assertEquals(Reason.NO_BOX, e.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `two boxes without a choice asks, and a choice proceeds`() = runBlocking {
        server.enqueue(MockResponse().setBody(TWO_BOXES))
        val p = HetznerProvisioner(api, transportFor = { fakeTransport {} }, clockDelay = {})
        val e = assertThrows(HetznerProvisioner.ProvisionException::class.java) { runBlocking { p.provision() } }
        assertEquals(Reason.CHOOSE_BOX, e.reason)
        assertEquals(listOf(42L, 43L), e.boxes.map { it.id })

        server.enqueue(MockResponse().setBody(TWO_BOXES))
        server.enqueue(MockResponse().setResponseCode(201).setBody(CREATED))
        server.enqueue(MockResponse().setBody("""{"action":{"id":99,"status":"success"}}"""))
        server.enqueue(MockResponse().setBody("""{"subaccount":{"id":7,"username":"u654321-sub1","server":"u654321-sub1.your-storagebox.de"}}"""))
        val creds = p.provision(chosenBoxId = 43)
        server.takeRequest(); server.takeRequest() // first attempt's list + this attempt's list
        assertEquals("/v1/storage_boxes/43/subaccounts", server.takeRequest().path)
        assertEquals("u654321-sub1", creds.username)
    }

    @Test
    fun `bad token is BAD_TOKEN`() {
        server.enqueue(MockResponse().setBody(ONE_BOX))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"nope"}}"""))
        val p = HetznerProvisioner(api, transportFor = { fakeTransport {} }, clockDelay = {})
        val e = assertThrows(HetznerProvisioner.ProvisionException::class.java) { runBlocking { p.provision() } }
        assertEquals(Reason.BAD_TOKEN, e.reason)
    }

    @Test
    fun `failed Hetzner action surfaces its message`() {
        server.enqueue(MockResponse().setBody(ONE_BOX))
        server.enqueue(MockResponse().setResponseCode(201).setBody(CREATED))
        server.enqueue(MockResponse().setBody("""{"action":{"id":99,"status":"error","error":{"code":"limit","message":"too many subaccounts"}}}"""))
        val p = HetznerProvisioner(api, transportFor = { fakeTransport {} }, clockDelay = {})
        val e = assertThrows(HetznerProvisioner.ProvisionException::class.java) { runBlocking { p.provision() } }
        assertEquals(Reason.HETZNER_ERROR, e.reason)
        assertTrue(e.message!!, e.message!!.contains("too many subaccounts"))
    }

    @Test
    fun `webdav never answering keeps the creds on the exception`() {
        enqueueHappyPath()
        val p = HetznerProvisioner(
            api,
            transportFor = { fakeTransport { throw TransportException(FailureKind.AUTH, "still 401") } },
            clockDelay = { delays += it },
        )
        val e = assertThrows(HetznerProvisioner.ProvisionException::class.java) { runBlocking { p.provision() } }
        assertEquals(Reason.WEBDAV_NOT_UP, e.reason)
        assertEquals("u123456-sub1", e.creds?.username)
        assertTrue(delays.sum() >= HetznerProvisioner.WEBDAV_WAIT_MAX_MS)
    }

    private fun fakeTransport(onTest: () -> Unit): Transport = object : Transport {
        override suspend fun testConnection() = onTest()
        override suspend fun ensureCollection(path: String) = Unit
        override suspend fun put(path: String, content: Content) = Unit
        override suspend fun list(path: String): List<RemoteEntry> = emptyList()
        override suspend fun get(path: String): InputStream = ByteArray(0).inputStream()
        override suspend fun delete(path: String) = Unit
    }

    private companion object {
        const val ONE_BOX = """{"storage_boxes":[{"id":42,"name":"box","server":"u123456.your-storagebox.de","username":"u123456","status":"active"}]}"""
        const val TWO_BOXES = """{"storage_boxes":[
            {"id":42,"name":"a","server":"u123456.your-storagebox.de","username":"u123456","status":"active"},
            {"id":43,"name":"b","server":"u654321.your-storagebox.de","username":"u654321","status":"active"}]}"""
        const val CREATED = """{"subaccount":{"id":7,"username":"","server":""},"action":{"id":99,"status":"running"}}"""
    }
}
