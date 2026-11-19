package io.agents.pokeclaw.auth

import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class AuthApiException(val status: Int, val code: String) : Exception("Authentication request failed ($status)")

internal class AuthApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun request(path: String, body: JSONObject? = null, accessToken: String? = null): JSONObject {
        val base = BuildConfig.AUTH_BASE_URL.toHttpUrl()
        require(base.isHttps || (BuildConfig.DEBUG && base.scheme == "http" &&
            base.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) {
            "Configure an HTTPS authentication server (debug builds also allow loopback)."
        }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        val url = base.newBuilder().addPathSegments(path.removePrefix("/")).build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
        XLog.d("AuthApi", "request: $path")
        client.newCall(builder.build()).execute().use { response ->
            XLog.d("AuthApi", "response: $path status=${response.code}")
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(text).optJSONObject("error")?.optString("code") }.getOrNull()
                throw AuthApiException(response.code, code.orEmpty())
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
