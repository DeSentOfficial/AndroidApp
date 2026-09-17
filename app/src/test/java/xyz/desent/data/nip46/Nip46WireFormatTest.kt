package xyz.desent.data.nip46

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the two NIP-46 transports. The GIFT_WRAP cases are
 * regression tests for the DeSent-internal object shape the web inbox parses;
 * the RAW cases assert the standard (stringified result / string error) shape
 * external clients (Amethyst/NDK, Damus, iris) expect.
 */
class Nip46WireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // RAW (standard kind-24133) responses
    // ------------------------------------------------------------------

    @Test
    fun `raw sign_event result is a stringified event`() {
        val signedEvent = """{"id":"abc","pubkey":"pk","created_at":1,"kind":1,"tags":[],"content":"hi","sig":"ff"}"""
        val encoded = encodeResponse(
            nip46PayloadResult("req-2", json.parseToJsonElement(signedEvent)),
            Nip46Transport.RAW
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        // result must be a STRING containing the JSON event (spec: json_stringified)
        assertEquals(signedEvent, obj["result"]!!.jsonPrimitive.content)
        assertNull(obj["error"])
    }

    @Test
    fun `raw plain-string results stay strings`() {
        val encoded = encodeResponse(nip46PayloadString("req-1", "deadbeefcafe"), Nip46Transport.RAW)
        assertEquals("deadbeefcafe", json.parseToJsonElement(encoded).jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `raw null result serializes as json null`() {
        val encoded = encodeResponse(nip46PayloadNull("req-9"), Nip46Transport.RAW)
        assertTrue(json.parseToJsonElement(encoded).jsonObject["result"] is JsonNull)
    }

    @Test
    fun `raw error is a plain string`() {
        val encoded = encodeResponse(
            nip46PayloadError("req-2", Nip46ErrorCode.DENIED, "user denied"),
            Nip46Transport.RAW
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("denied: user denied", obj["error"]!!.jsonPrimitive.content)
        assertNull(obj["result"])
    }

    @Test
    fun `raw ack and pong are strings`() {
        assertEquals(
            "ack",
            json.parseToJsonElement(encodeResponse(nip46PayloadString("c1", "ack"), Nip46Transport.RAW))
                .jsonObject["result"]!!.jsonPrimitive.content
        )
        assertEquals(
            "pong",
            json.parseToJsonElement(encodeResponse(nip46PayloadString("p1", "pong"), Nip46Transport.RAW))
                .jsonObject["result"]!!.jsonPrimitive.content
        )
    }

    // ------------------------------------------------------------------
    // GIFT_WRAP (DeSent internal) responses — web-inbox regression
    // ------------------------------------------------------------------

    @Test
    fun `giftwrap result passes the object through verbatim`() {
        val eventObj = json.parseToJsonElement("""{"id":"abc","kind":27235}""")
        val encoded = encodeResponse(nip46PayloadResult("req-2", eventObj), Nip46Transport.GIFT_WRAP)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals(eventObj, obj["result"])
    }

    @Test
    fun `giftwrap error stays a code-message object`() {
        val encoded = encodeResponse(
            nip46PayloadError("req-2", Nip46ErrorCode.POLICY, "kind 4 not allowed"),
            Nip46Transport.GIFT_WRAP
        )
        val error = json.parseToJsonElement(encoded).jsonObject["error"]!!.jsonObject
        assertEquals(Nip46ErrorCode.POLICY, error["code"]!!.jsonPrimitive.content)
        assertEquals("kind 4 not allowed", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy giftwrap builders keep their shape`() {
        val resultJson = json.parseToJsonElement(nip46StringResult("r1", "pk"))
        assertEquals("pk", resultJson.jsonObject["result"]!!.jsonPrimitive.content)

        val errorJson = json.parseToJsonElement(nip46Error("r1", "denied", "user denied")).jsonObject
        val error = errorJson["error"]!!.jsonObject
        assertEquals("denied", error["code"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // sign_event params[0]: string-JSON (standard) vs object (DeSent web)
    // ------------------------------------------------------------------

    @Test
    fun `string-Json event param parses`() {
        val param = JsonPrimitive("""{"kind":1,"content":"hello","tags":[],"created_at":1714078911}""")
        val obj = parseUnsignedEventParam(param)!!
        assertEquals(1L, obj["kind"]!!.jsonPrimitive.content.toLong())
        assertEquals("hello", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `object event param parses`() {
        val param = json.parseToJsonElement("""{"kind":22242,"content":"","tags":[]}""")
        val obj = parseUnsignedEventParam(param)!!
        assertEquals(22242L, obj["kind"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `invalid event param returns null`() {
        assertNull(parseUnsignedEventParam(JsonPrimitive("not json")))
        assertNull(parseUnsignedEventParam(JsonNull))
    }

    @Test
    fun `readKind handles both param shapes`() {
        val asString: kotlinx.serialization.json.JsonElement = JsonPrimitive("""{"kind":13}""")
        val asObject: kotlinx.serialization.json.JsonElement = json.parseToJsonElement("""{"kind":14}""")
        assertEquals(13L, asString.readKind())
        assertEquals(14L, asObject.readKind())
    }

    // ------------------------------------------------------------------
    // perms parsing
    // ------------------------------------------------------------------

    @Test
    fun `perms list parses methods and kinds`() {
        val perms = parsePerms("nip44_encrypt, sign_event:13,sign_event:14 , sign_event:1059,get_public_key")
        assertEquals(
            listOf(
                Nip46Perm("nip44_encrypt"),
                Nip46Perm("sign_event", 13),
                Nip46Perm("sign_event", 14),
                Nip46Perm("sign_event", 1059),
                Nip46Perm("get_public_key")
            ),
            perms
        )
    }

    @Test
    fun `blank perms parse to empty`() {
        assertTrue(parsePerms(null).isEmpty())
        assertTrue(parsePerms("").isEmpty())
    }
}
