package de.riese.rieseguard

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppLimitInfo(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("limit_minutes") val limitMinutes: Int
)

data class PolicyResponse(
    @SerializedName("locked") val locked: Boolean,
    @SerializedName("lock_reason") val lockReason: String? = null,
    @SerializedName("blocked_packages") val blockedPackages: List<String>? = null,
    @SerializedName("schedule_active") val scheduleActive: Boolean = false,
    @SerializedName("schedule_start") val scheduleStart: String? = null,
    @SerializedName("schedule_end") val scheduleEnd: String? = null,
    @SerializedName("latest_apk_version") val latestApkVersion: Int? = null,
    @SerializedName("latest_apk_url") val latestApkUrl: String? = null,
    @SerializedName("web_filter_active") val webFilterActive: Boolean = false,
    @SerializedName("app_limits") val appLimits: List<AppLimitInfo>? = null,
    @SerializedName("school_active") val schoolActive: Boolean = false,
    @SerializedName("school_start") val schoolStart: String? = null,
    @SerializedName("school_end") val schoolEnd: String? = null
)

data class AppInfo(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String,
    @SerializedName("usage_minutes") val usageMinutes: Int = 0
)

class PolicyRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    @Throws(IOException::class)
    fun getPolicy(serverUrl: String, deviceId: Int, token: String, lat: Double? = null, lng: Double? = null): PolicyResponse? {
        val formattedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "http://$serverUrl"
        } else {
            serverUrl
        }
        
        val cleanUrl = formattedUrl.trimEnd('/')
        var url = "$cleanUrl/devices/$deviceId/policy?token=$token"
        if (lat != null && lng != null) {
            url += "&lat=$lat&lng=$lng"
        }

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

    fun downloadApk(serverUrl: String, apkUrlPath: String, destFile: java.io.File): Boolean {
        val formattedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "http://$serverUrl"
        } else {
            serverUrl
        }
        val cleanServer = formattedUrl.trimEnd('/')
        val cleanPath = apkUrlPath.trimStart('/')
        
        val url = if (apkUrlPath.startsWith("http")) {
            apkUrlPath
        } else {
            "$cleanServer/$cleanPath"
        }
        
        val request = Request.Builder()
            .url(url)
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                destFile.outputStream().use { output ->
                    body.byteStream().copyTo(output)
                }
                true
            }
        } catch (e: Exception) {
            Log.e("PolicyRepository", "Error downloading APK", e)
            false
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
