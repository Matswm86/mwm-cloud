package no.mwmai.mwmcloud.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HetznerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HetznerApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = HetznerApi(token = "tok-123", baseUrl = server.url("/v1").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `listStorageBoxes sends bearer token and parses boxes with unknown keys`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"storage_boxes":[{"id":42,"name":"my-box","server":"u123456.your-storagebox.de",
                   "username":"u123456","status":"active","location":{"name":"fsn1","city":"Falkenstein"},
                   "storage_box_type":{"name":"bx11","description":"BX11"},
                   "access_settings":{"webdav_enabled":false,"reachable_externally":true,"zfs_enabled":false},
                   "stats":{"size":1},"future_field":{"x":1}}],"meta":{"pagination":{}}}""",
            ),
        )
        val boxes = api.listStorageBoxes()
        val req = server.takeRequest()
        assertEquals("/v1/storage_boxes", req.path)
        assertEquals("Bearer tok-123", req.getHeader("Authorization"))
        assertEquals(1, boxes.size)
        assertEquals(42L, boxes[0].id)
        assertEquals("u123456", boxes[0].username)
        assertEquals("my-box · BX11 · Falkenstein", boxes[0].label)
    }

    @Test
    fun `createSubaccount posts webdav-enabled body and returns subaccount plus action`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"subaccount":{"id":7,"username":"u123456-sub1","server":"u123456-sub1.your-storagebox.de",
                   "home_directory":"mwmcloud","access_settings":{"webdav_enabled":true}},
                   "action":{"id":99,"status":"running","error":null}}""",
            ),
        )
        val r = api.createSubaccount(42, "mwmcloud", "Secret1234", "MWM Cloud")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/storage_boxes/42/subaccounts", req.path)
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"home_directory\":\"mwmcloud\""))
        assertTrue(body, body.contains("\"password\":\"Secret1234\""))
        assertTrue(body, body.contains("\"webdav_enabled\":true"))
        assertTrue(body, body.contains("\"reachable_externally\":true"))
        assertEquals("u123456-sub1", r.subaccount.username)
        assertEquals(99L, r.action.id)
        assertTrue(r.action.isRunning)
    }

    @Test
    fun `getAction hits actions endpoint and exposes error`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"action":{"id":99,"status":"error","error":{"code":"x","message":"boom"}}}"""))
        val a = api.getAction(99)
        assertEquals("/v1/storage_boxes/actions/99", server.takeRequest().path)
        assertEquals("boom", a.error?.message)
        assertTrue(!a.isRunning && !a.isSuccess)
    }

    @Test
    fun `401 maps to AUTH with Hetzner message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"unable to authenticate"}}"""))
        val e = assertThrows(HetznerApiException::class.java) { runBlocking { api.listStorageBoxes() } }
        assertEquals(FailureKind.AUTH, e.kind)
        assertTrue(e.message!!, e.message!!.contains("unable to authenticate"))
    }

    @Test
    fun `unreachable host maps to NETWORK`() {
        val dead = HetznerApi(token = "t", baseUrl = "http://127.0.0.1:1/v1")
        val e = assertThrows(HetznerApiException::class.java) { runBlocking { dead.listStorageBoxes() } }
        assertEquals(FailureKind.NETWORK, e.kind)
    }
}
