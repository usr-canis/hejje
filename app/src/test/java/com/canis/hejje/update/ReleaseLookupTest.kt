package com.canis.hejje.update

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReleaseLookupTest {
    private fun client(code: Int, body: String = "") = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()

    @Test fun missingReleaseIsNotAConnectionFailure() {
        assertEquals(ReleaseLookup.NotFound, fetchRelease(client(404), "https://example.com/latest"))
    }

    @Test fun validReleaseIsParsed() {
        val result = fetchRelease(client(200, """{"tag_name":"v1.2.3"}"""), "https://example.com/latest")
        assertTrue(result is ReleaseLookup.Found && result.release.tag == "v1.2.3")
    }

    @Test fun serviceAndNetworkFailuresAreNotMissingReleases() {
        assertEquals(ReleaseLookup.Failed, fetchRelease(client(403), "https://example.com/latest"))
        val offline = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("offline") }).build()
        assertEquals(ReleaseLookup.Failed, fetchRelease(offline, "https://example.com/latest"))
    }
}
