package de.riese.rieseguard

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PolicyResponse(
    @SerializedName("locked") val locked: Boolean,
    @SerializedName("lock_reason") val lockReason: String? = null,
    @SerializedName("blocked_packages") val blockedPackages: List<String>? = null,
    @SerializedName("schedule_active") val scheduleActive: Boolean = false,
    @SerializedName("schedule_start") val scheduleStart: String? = null,
    @SerializedName("schedule_end") val scheduleEnd: String? = null
)

data class AppInfo(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String
)

class PolicyRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    @Throws(IOException::class)
    fun getPolicy(serverUrl: String, deviceId: Int, token: String): PolicyResponse? {
        val formattedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "http://$serverUrl"
        } else {
            serverUrl
        }
        
        val cleanUrl = formattedUrl.trimEnd('/')
        val url = "$cleanUrl/devices/$deviceId/policy?token=$token"

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP response: ${response.code}")
            }
            val bodyString = response.body?.string() ?: return null
            return gson.fromJson(bodyString, PolicyResponse::class.java)
        }
    }

    fun uploadInstalledApps(serverUrl: String, deviceId: Int, token: String, apps: List<AppInfo>): Boolean {
        val formattedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "http://$serverUrl"
        } else {
            serverUrl
        }
        
        val cleanUrl = formattedUrl.trimEnd('/')
        val url = "$cleanUrl/devices/$deviceId/apps?token=$token"
        
        val appsJson = gson.toJson(apps)
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = okhttp3.RequestBody.create(
            mediaType,
            appsJson
        )
        
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("PolicyRepository", "Error uploading apps", e)
            false
        }
    }
}
