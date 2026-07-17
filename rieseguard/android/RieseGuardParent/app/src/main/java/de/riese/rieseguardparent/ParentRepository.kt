package de.riese.rieseguardparent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// Data models
data class DeviceInfo(
    val id: Int,
    val name: String,
    @SerializedName("device_token") val deviceToken: String,
    val locked: Boolean,
    @SerializedName("last_seen") val lastSeen: String?,
    @SerializedName("lock_reason") val lockReason: String?,
    @SerializedName("schedule_active") val scheduleActive: Boolean,
    @SerializedName("schedule_start") val scheduleStart: String?,
    @SerializedName("schedule_end") val scheduleEnd: String?,
    val latitude: Double?,
    val longitude: Double?,
    @SerializedName("location_updated") val locationUpdated: String?,
    @SerializedName("web_filter_active") val webFilterActive: Boolean,
    @SerializedName("school_active") val schoolActive: Boolean,
    @SerializedName("school_start") val schoolStart: String?,
    @SerializedName("school_end") val schoolEnd: String?,
    @SerializedName("today_usage_minutes") val todayUsageMinutes: Int? = 0,
    val apps: List<AppInfo>? = emptyList()
)

data class AppInfo(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_name") val appName: String,
    @SerializedName("is_blocked") var isBlocked: Boolean,
    @SerializedName("usage_minutes") val usageMinutes: Int = 0
)

data class AppLimitInfo(
    val id: Int,
    @SerializedName("device_id") val deviceId: Int,
    @SerializedName("package_name") val packageName: String,
    @SerializedName("daily_limit_minutes") val dailyLimitMinutes: Int
)

class ParentRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private var authToken: String? = null

    fun setToken(token: String?) {
        this.authToken = token
    }

    private fun getAuthHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        authToken?.let {
            headers["Authorization"] = "Bearer $it"
        }
        return headers
    }

    fun register(serverUrl: String, email: String, password: String): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/register"
            val json = gson.toJson(mapOf("email" to email, "password" to password))
            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Register failed", e)
            false
        }
    }

    fun login(serverUrl: String, email: String, password: String): String? {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/token"
            val formBody = okhttp3.FormBody.Builder()
                .add("username", email)
                .add("password", password)
                .build()
            val request = Request.Builder().url(url).post(formBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val map = gson.fromJson(body, Map::class.java)
                val token = map["access_token"] as? String
                authToken = token
                token
            }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Login failed", e)
            null
        }
    }

    fun listDevices(serverUrl: String): List<DeviceInfo> {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices"
            val requestBuilder = Request.Builder().url(url)
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                gson.fromJson(body, Array<DeviceInfo>::class.java).toList()
            }
        } catch (e: Exception) {
            Log.e("ParentRepo", "List devices failed", e)
            emptyList()
        }
    }

    fun addDevice(serverUrl: String, name: String): DeviceInfo? {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices?name=${java.net.URLEncoder.encode(name, "UTF-8")}"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                gson.fromJson(body, DeviceInfo::class.java)
            }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Add device failed", e)
            null
        }
    }

    fun toggleLock(serverUrl: String, deviceId: Int, lock: Boolean, reason: String = ""): Boolean {
        return try {
            val path = if (lock) "lock?reason=${java.net.URLEncoder.encode(reason, "UTF-8")}" else "unlock"
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/$path"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Toggle lock failed", e)
            false
        }
    }

    fun setSchedule(serverUrl: String, deviceId: Int, active: Boolean, start: String, end: String): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/schedule?schedule_active=$active&schedule_start=${java.net.URLEncoder.encode(start, "UTF-8")}&schedule_end=${java.net.URLEncoder.encode(end, "UTF-8")}"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Set schedule failed", e)
            false
        }
    }

    fun setSchool(serverUrl: String, deviceId: Int, active: Boolean, start: String, end: String): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/school?school_active=$active&school_start=${java.net.URLEncoder.encode(start, "UTF-8")}&school_end=${java.net.URLEncoder.encode(end, "UTF-8")}"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Set school mode failed", e)
            false
        }
    }

    fun toggleWebFilter(serverUrl: String, deviceId: Int, active: Boolean): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/webfilter?web_filter_active=$active"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Toggle web filter failed", e)
            false
        }
    }

    fun listApps(serverUrl: String, deviceId: Int): List<AppInfo> {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/apps"
            val requestBuilder = Request.Builder().url(url)
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                gson.fromJson(body, Array<AppInfo>::class.java).toList()
            }
        } catch (e: Exception) {
            Log.e("ParentRepo", "List apps failed", e)
            emptyList()
        }
    }

    fun toggleAppBlock(serverUrl: String, deviceId: Int, packageName: String, blocked: Boolean): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/apps/toggle?package_name=${java.net.URLEncoder.encode(packageName, "UTF-8")}&is_blocked=$blocked"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Toggle app block failed", e)
            false
        }
    }

    fun getLimits(serverUrl: String, deviceId: Int): List<AppLimitInfo> {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/limits"
            val requestBuilder = Request.Builder().url(url)
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                gson.fromJson(body, Array<AppLimitInfo>::class.java).toList()
            }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Get limits failed", e)
            emptyList()
        }
    }

    fun saveLimit(serverUrl: String, deviceId: Int, packageName: String, minutes: Int): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/limits"
            val json = gson.toJson(mapOf("package_name" to packageName, "daily_limit_minutes" to minutes))
            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val requestBuilder = Request.Builder().url(url).post(body)
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Save limit failed", e)
            false
        }
    }

    fun deleteLimit(serverUrl: String, deviceId: Int, packageName: String): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId/limits/delete?package_name=${java.net.URLEncoder.encode(packageName, "UTF-8")}"
            val requestBuilder = Request.Builder().url(url).post("".toRequestBody())
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Delete limit failed", e)
            false
        }
    }

    fun deleteDevice(serverUrl: String, deviceId: Int): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/devices/$deviceId"
            val requestBuilder = Request.Builder().url(url).delete()
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Delete device failed", e)
            false
        }
    }

    fun deleteAccount(serverUrl: String): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/account"
            val requestBuilder = Request.Builder().url(url).delete()
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Delete account failed", e)
            false
        }
    }

    fun uploadApk(serverUrl: String, versionCode: Int, versionName: String, apkBytes: ByteArray, fileName: String): Boolean {
        return try {
            val url = "${formatUrl(serverUrl)}/parent/upload-apk"
            val mediaType = "application/vnd.android.package-archive".toMediaTypeOrNull()
            val fileBody = RequestBody.create(mediaType, apkBytes)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("version_code", versionCode.toString())
                .addFormDataPart("version_name", versionName)
                .addFormDataPart("file", fileName, fileBody)
                .build()
            val requestBuilder = Request.Builder().url(url).post(requestBody)
            getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e("ParentRepo", "Upload APK failed", e)
            false
        }
    }

    private fun formatUrl(url: String): String {
        val clean = url.trim()
        val withProtocol = if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            "http://$clean"
        } else {
            clean
        }
        return withProtocol.trimEnd('/')
    }
}
