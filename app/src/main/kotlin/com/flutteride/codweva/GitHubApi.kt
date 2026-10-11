package com.flutteride.codweva

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * GitHubApi — كل الاتصالات بـ GitHub REST API.
 *
 * v2.0 — يدعم أنواع مشاريع متعددة:
 *   - Flutter (main.yml + app-release)
 *   - Java    (build-android.yml + klencod-java-debug-apk)
 *   - Kotlin  (build-android.yml + klencod-kotlin-debug-apk) — لاحقاً
 *
 * التغييرات عن v1.0:
 *   - triggerBuild يستقبل workflowFile parameter
 *   - latestArtifact يستقبل artifactName parameter
 *   - التوافق الكامل مع النداءات القديمة عبر Deprecated overloads
 */
object GitHubApi {

    // ============================================================
    // Callbacks
    // ============================================================
    interface Callback<T> {
        fun onSuccess(value: T)
        fun onError(message: String)
    }

    /**
     * واجهة تحميل مع تقدم.
     */
    interface DownloadCallback<T> {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onSuccess(value: T)
        fun onError(message: String)
    }

    // ============================================================
    // نماذج البيانات
    // ============================================================
    class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    class Repository(
        val owner: String,
        val name: String,
        val htmlUrl: String,
        val defaultBranch: String
    )

    class Run @JvmOverloads constructor(
        val id: Long,
        val status: String,
        val conclusion: String,
        val htmlUrl: String,
        val createdAtMs: Long = 0L,
        val headSha: String = ""
    )

    class Artifact(
        val id: Long,
        val name: String,
        val size: Long,
        val expired: Boolean
    )

    class Step @JvmOverloads constructor(
        val number: Int,
        val name: String,
        val status: String,
        val conclusion: String,
        val startedAtMs: Long,
        val completedAtMs: Long
    ) {
        fun durationMs(): Long {
            if (startedAtMs <= 0 || completedAtMs <= 0) return 0L
            return maxOf(0L, completedAtMs - startedAtMs)
        }
    }

    class Job @JvmOverloads constructor(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String,
        val startedAtMs: Long,
        val completedAtMs: Long,
        val steps: List<Step> = emptyList()
    )

    // ============================================================
    // ExecutorService مشترك
    // ============================================================
    private val EXECUTOR: ExecutorService = Executors.newFixedThreadPool(3)

    // ============================================================
    // Device Flow
    // ============================================================
    @JvmStatic
    fun requestDeviceCode(callback: Callback<DeviceCode>) {
        run {
            try {
                requireClientId()
                val form = "client_id=${enc(GitHubConfig.CLIENT_ID)}" +
                        "&scope=${enc(GitHubConfig.OAUTH_SCOPE)}"
                val data = requestJson(
                    "POST", GitHubConfig.DEVICE_CODE_URL, "", form, true
                )

                callback.onSuccess(
                    DeviceCode(
                        data.getString("device_code"),
                        data.getString("user_code"),
                        data.optString("verification_uri", GitHubConfig.VERIFICATION_URL),
                        data.optInt("expires_in", 900),
                        data.optInt("interval", 5)
                    )
                )
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    @JvmStatic
    fun pollAccessToken(device: DeviceCode, callback: Callback<String>) {
        run {
            try {
                requireClientId()
                val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
                var waitSeconds = maxOf(5, device.interval)

                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(waitSeconds * 1000L)

                    val form = "client_id=${enc(GitHubConfig.CLIENT_ID)}" +
                            "&device_code=${enc(device.deviceCode)}" +
                            "&grant_type=urn:ietf:params:oauth:grant-type:device_code"

                    val data = requestJson(
                        "POST", GitHubConfig.ACCESS_TOKEN_URL, "", form, true
                    )

                    if (data.has("access_token")) {
                        callback.onSuccess(data.getString("access_token"))
                        return@run
                    }

                    val error = data.optString("error", "authorization_pending")
                    when (error) {
                        "authorization_pending" -> continue
                        "slow_down" -> {
                            waitSeconds += 5
                            continue
                        }
                        "access_denied" -> throw Exception("رفض المستخدم التفويض")
                        "expired_token" -> throw Exception("انتهت صلاحية رمز الجهاز")
                        else -> throw Exception(
                            data.optString("error_description", error)
                        )
                    }
                }
                throw Exception("انتهت مهلة تسجيل الدخول")
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    @JvmStatic
    fun getUser(token: String, callback: Callback<String>) {
        run {
            try {
                val data = requestJson(
                    "GET", "${GitHubConfig.API_URL}/user",
                    token, null, false
                )
                callback.onSuccess(data.optString("login", "GitHub user"))
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    // ============================================================
    // Repositories
    // ============================================================
    @JvmStatic
    fun createRepository(
        token: String,
        name: String,
        isPrivate: Boolean,
        callback: Callback<Repository>
    ) {
        run {
            try {
                val body = JSONObject().apply {
                    put("name", name)
                    put("description", "Project created by KLENCOD IDE")
                    put("private", isPrivate)
                    put("auto_init", true)
                }

                val data = requestJson(
                    "POST", "${GitHubConfig.API_URL}/user/repos",
                    token, body.toString(), false
                )

                val owner = data.optJSONObject("owner")
                callback.onSuccess(
                    Repository(
                        owner?.optString("login", "") ?: "",
                        data.optString("name", name),
                        data.optString("html_url", ""),
                        data.optString("default_branch", "main")
                    )
                )
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    @JvmStatic
    fun getRepository(
        token: String,
        owner: String,
        name: String,
        callback: Callback<Repository>
    ) {
        run {
            try {
                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(name)}"
                val data = requestJson("GET", url, token, null, false)
                val repositoryOwner = data.optJSONObject("owner")

                callback.onSuccess(
                    Repository(
                        repositoryOwner?.optString("login", owner) ?: owner,
                        data.optString("name", name),
                        data.optString("html_url", ""),
                        data.optString("default_branch", "main")
                    )
                )
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    // ============================================================
    // Upload files
    // ============================================================
    @JvmStatic
    fun uploadFile(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String,
        content: String,
        commitMessage: String,
        callback: Callback<Boolean>
    ) {
        run {
            try {
                val body = JSONObject().apply {
                    put("message", commitMessage)
                    put(
                        "content",
                        Base64.encodeToString(
                            content.toByteArray(StandardCharsets.UTF_8),
                            Base64.NO_WRAP
                        )
                    )
                    put("branch", branch)

                    val currentSha = existingFileSha(token, owner, repo, branch, path)
                    if (currentSha.isNotEmpty()) put("sha", currentSha)
                }
                requestJson(
                    "PUT", contentUrl(owner, repo, path),
                    token, body.toString(), false
                )
                callback.onSuccess(true)
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    @JvmStatic
    fun uploadBytes(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String,
        content: ByteArray?,
        commitMessage: String,
        callback: Callback<Boolean>
    ) {
        run {
            try {
                val body = JSONObject().apply {
                    put("message", commitMessage)
                    put(
                        "content",
                        Base64.encodeToString(
                            content ?: ByteArray(0),
                            Base64.NO_WRAP
                        )
                    )
                    put("branch", branch)

                    val currentSha = existingFileSha(token, owner, repo, branch, path)
                    if (currentSha.isNotEmpty()) put("sha", currentSha)
                }
                requestJson(
                    "PUT", contentUrl(owner, repo, path),
                    token, body.toString(), false
                )
                callback.onSuccess(true)
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    // ============================================================
    // Triggers — النسخة القديمة (للتوافق)
    // ============================================================
    @Deprecated("Use version with workflowFile")
    @JvmStatic
    fun triggerBuild(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        callback: Callback<Boolean>
    ) {
        triggerBuild(
            token, owner, repo, branch,
            GitHubConfig.WORKFLOW_FILE, "3.47.2", callback
        )
    }

    @Deprecated("Use version with workflowFile")
    @JvmStatic
    fun triggerBuild(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        flutterVersion: String?,
        callback: Callback<Boolean>
    ) {
        triggerBuild(
            token, owner, repo, branch,
            GitHubConfig.WORKFLOW_FILE, flutterVersion, callback
        )
    }

    // ============================================================
    // Triggers — النسخة الجديدة (تدعم أي workflow)
    // ============================================================
    @JvmStatic
    fun triggerBuild(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        workflowFile: String,           // ⭐ NEW
        flutterVersion: String?,        // يُستخدم فقط لـ Flutter
        callback: Callback<Boolean>
    ) {
        run {
            try {
                val v = if (flutterVersion.isNullOrBlank()) "3.47.2" else flutterVersion.trim()

                val body = JSONObject().apply {
                    put("ref", branch)

                    // Flutter يحتاج inputs.flutter_version
                    // Java/Kotlin لا يحتاج inputs
                    val isFlutter = workflowFile == "main.yml" ||
                            workflowFile.contains("flutter", ignoreCase = true)

                    if (isFlutter) {
                        put("inputs", JSONObject().apply {
                            put("flutter_version", v)
                        })
                    }
                }

                val safeWorkflow = workflowFile.ifBlank { GitHubConfig.WORKFLOW_FILE }

                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                        "/actions/workflows/${encPath(safeWorkflow)}/dispatches"

                requestJson("POST", url, token, body.toString(), false)
                callback.onSuccess(true)
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    // ============================================================
    // Runs
    // ============================================================
    @Deprecated("Use version with workflowFile")
    @JvmStatic
    fun latestRun(
        token: String,
        owner: String,
        repo: String,
        notBeforeMs: Long,
        callback: Callback<Run>
    ) {
        latestRun(token, owner, repo, notBeforeMs, GitHubConfig.WORKFLOW_FILE, callback)
    }

    @JvmStatic
    fun latestRun(
        token: String,
        owner: String,
        repo: String,
        notBeforeMs: Long,
        workflowFile: String,           // ⭐ NEW
        callback: Callback<Run>
    ) {
        run {
            try {
                val safeWorkflow = workflowFile.ifBlank { GitHubConfig.WORKFLOW_FILE }

                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                        "/actions/workflows/${encPath(safeWorkflow)}/runs" +
                        "?event=workflow_dispatch&per_page=20"

                val data = requestJson("GET", url, token, null, false)
                val runs = data.optJSONArray("workflow_runs")
                    ?: throw Exception("لا توجد عملية بناء بعد")

                if (runs.length() == 0) throw Exception("لا توجد عملية بناء بعد")

                for (i in 0 until runs.length()) {
                    val item = runs.getJSONObject(i)
                    val createdAt = parseIsoTime(item.optString("created_at", ""))

                    if (notBeforeMs <= 0L || createdAt >= notBeforeMs - 15000L) {
                        callback.onSuccess(
                            Run(
                                item.optLong("id"),
                                item.optString("status"),
                                item.optString("conclusion"),
                                item.optString("html_url"),
                                createdAt,
                                item.optString("head_sha", "")
                            )
                        )
                        return@run
                    }
                }
                throw Exception("تم تشغيل Workflow لكن لم تظهر العملية الجديدة بعد")
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    @JvmStatic
    fun getRun(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        callback: Callback<Run>
    ) {
        run {
            try {
                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                        "/actions/runs/$runId"

                val item = requestJson("GET", url, token, null, false)

                callback.onSuccess(
                    Run(
                        item.optLong("id", runId),
                        item.optString("status"),
                        item.optString("conclusion"),
                        item.optString("html_url"),
                        parseIsoTime(item.optString("created_at", "")),
                        item.optString("head_sha", "")
                    )
                )
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    @JvmStatic
    fun getRunJobs(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        callback: Callback<List<Job>>
    ) {
        run {
            try {
                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                        "/actions/runs/$runId/jobs"

                val data = requestJson("GET", url, token, null, false)
                val jobsArray = data.optJSONArray("jobs")
                val jobs = mutableListOf<Job>()

                if (jobsArray != null) {
                    for (i in 0 until jobsArray.length()) {
                        val jobJson = jobsArray.optJSONObject(i) ?: continue

                        val steps = mutableListOf<Step>()
                        val stepsArray = jobJson.optJSONArray("steps")
                        if (stepsArray != null) {
                            for (j in 0 until stepsArray.length()) {
                                val stepJson = stepsArray.optJSONObject(j) ?: continue
                                steps.add(
                                    Step(
                                        stepJson.optInt("number", j + 1),
                                        stepJson.optString("name", ""),
                                        stepJson.optString("status", ""),
                                        stepJson.optString("conclusion", ""),
                                        parseIsoTime(stepJson.optString("started_at", "")),
                                        parseIsoTime(stepJson.optString("completed_at", ""))
                                    )
                                )
                            }
                        }

                        jobs.add(
                            Job(
                                jobJson.optLong("id", 0L),
                                jobJson.optString("name", ""),
                                jobJson.optString("status", ""),
                                jobJson.optString("conclusion", ""),
                                parseIsoTime(jobJson.optString("started_at", "")),
                                parseIsoTime(jobJson.optString("completed_at", "")),
                                steps
                            )
                        )
                    }
                }
                callback.onSuccess(jobs)
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    // ============================================================
    // Annotations
    // ============================================================
    @JvmStatic
    fun getRunAnnotations(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        callback: Callback<List<String>>
    ) {
        run {
            val messages = mutableListOf<String>()
            try {
                val runUrl = "${GitHubConfig.API_URL}/repos/${encPath(owner)}" +
                        "/${encPath(repo)}/actions/runs/$runId"

                val runJson = requestJson("GET", runUrl, token, null, false)
                val headSha = runJson.optString("head_sha", "")
                if (headSha.isEmpty()) {
                    callback.onSuccess(messages)
                    return@run
                }

                val checksUrl = "${GitHubConfig.API_URL}/repos/${encPath(owner)}" +
                        "/${encPath(repo)}/commits/$headSha/check-runs"

                val checksJson = requestJson("GET", checksUrl, token, null, false)
                val checkRuns = checksJson.optJSONArray("check_runs")

                if (checkRuns == null || checkRuns.length() == 0) {
                    callback.onSuccess(messages)
                    return@run
                }

                for (i in 0 until checkRuns.length()) {
                    val check = checkRuns.optJSONObject(i) ?: continue
                    val checkId = check.optLong("id", 0L)
                    if (checkId <= 0L) continue

                    val annotUrl = "${GitHubConfig.API_URL}/repos/${encPath(owner)}" +
                            "/${encPath(repo)}/check-runs/$checkId/annotations"

                    try {
                        val annotations = requestJsonArray("GET", annotUrl, token, null, false)
                        if (annotations != null) {
                            for (j in 0 until annotations.length()) {
                                val annot = annotations.optJSONObject(j) ?: continue
                                val msg = annot.optString("message", "")
                                val title = annot.optString("title", "")

                                var combined = msg
                                if (title.isNotEmpty() && !msg.startsWith(title)) {
                                    combined = "$title\n$msg"
                                }
                                if (combined.trim().isNotEmpty() && !messages.contains(combined)) {
                                    messages.add(combined.trim())
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                callback.onSuccess(messages)
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    // ============================================================
    // Logs
    // ============================================================
    @JvmStatic
    fun downloadJobLogs(
        token: String,
        owner: String,
        repo: String,
        jobId: Long,
        callback: Callback<String>
    ) {
        run {
            var connection: HttpURLConnection? = null
            try {
                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}" +
                        "/${encPath(repo)}/actions/jobs/$jobId/logs"

                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 120000
                    readTimeout = 120000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty(
                        "Accept",
                        "application/vnd.github+json, text/plain, application/zip"
                    )
                    setRequestProperty("X-GitHub-Api-Version", GitHubConfig.API_VERSION)
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    throw Exception("تعذر تنزيل سجل الخطوة، HTTP $status")
                }

                val rawOutput = ByteArrayOutputStream()
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        rawOutput.write(buffer, 0, count)
                    }
                }
                val payload = rawOutput.toByteArray()
                val contentType = connection.getHeaderField("Content-Type")

                val logs = if (isZipPayload(payload) ||
                    (contentType != null && contentType.lowercase(Locale.US).contains("zip"))
                ) {
                    unzipJobLogs(payload)
                } else {
                    String(payload, StandardCharsets.UTF_8)
                }

                callback.onSuccess(logs)
            } catch (error: Exception) {
                callback.onError(message(error))
            } finally {
                connection?.disconnect()
            }
        }
    }

    @JvmStatic
    fun downloadRunLogs(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        callback: Callback<String>
    ) {
        run {
            var connection: HttpURLConnection? = null
            try {
                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}" +
                        "/${encPath(repo)}/actions/runs/$runId/logs"

                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 120000
                    readTimeout = 120000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty(
                        "Accept",
                        "application/vnd.github+json, application/zip, text/plain"
                    )
                    setRequestProperty("X-GitHub-Api-Version", GitHubConfig.API_VERSION)
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    throw Exception("تعذر تنزيل سجل البناء، HTTP $status")
                }

                val rawOutput = ByteArrayOutputStream()
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        rawOutput.write(buffer, 0, count)
                    }
                }
                val payload = rawOutput.toByteArray()
                if (payload.isEmpty()) {
                    throw Exception("أعاد GitHub سجلًا فارغًا لهذه العملية")
                }

                val contentType = connection.getHeaderField("Content-Type")
                val logs = if (isZipPayload(payload) ||
                    (contentType != null && contentType.lowercase(Locale.US).contains("zip"))
                ) {
                    unzipRunLogs(payload)
                } else {
                    String(payload, StandardCharsets.UTF_8)
                }

                if (logs.isBlank()) {
                    throw Exception("تم تنزيل أرشيف سجل GitHub لكنه لا يحتوي على ملفات نصية")
                }

                callback.onSuccess(logs)
            } catch (error: Exception) {
                callback.onError(message(error))
            } finally {
                connection?.disconnect()
            }
        }
    }

    // ============================================================
    // Artifacts — النسخة القديمة (للتوافق)
    // ============================================================
    @Deprecated("Use version with artifactName")
    @JvmStatic
    fun latestArtifact(
        token: String,
        owner: String,
        repo: String,
        callback: Callback<Artifact>
    ) {
        latestArtifact(token, owner, repo, GitHubConfig.ARTIFACT_NAME, callback)
    }

    // ============================================================
    // Artifacts — النسخة الجديدة
    // ============================================================
    @JvmStatic
    fun latestArtifact(
        token: String,
        owner: String,
        repo: String,
        artifactName: String,           // ⭐ NEW
        callback: Callback<Artifact>
    ) {
        run {
            try {
                val safeName = artifactName.ifBlank { GitHubConfig.ARTIFACT_NAME }

                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                        "/actions/artifacts?name=${encPath(safeName)}&per_page=1"

                val data = requestJson("GET", url, token, null, false)
                val artifacts = data.optJSONArray("artifacts")

                if (artifacts == null || artifacts.length() == 0) {
                    throw Exception("لم يتم العثور على APK (artifact: $safeName)")
                }

                val item = artifacts.getJSONObject(0)
                callback.onSuccess(
                    Artifact(
                        item.optLong("id"),
                        item.optString("name"),
                        item.optLong("size_in_bytes"),
                        item.optBoolean("expired")
                    )
                )
            } catch (error: Exception) {
                callback.onError(message(error))
            }
        }
    }

    /**
     * النسخة القديمة (بدون تقدم).
     */
    @Deprecated("Use version with DownloadCallback")
    @JvmStatic
    fun downloadArtifact(
        token: String,
        owner: String,
        repo: String,
        artifactId: Long,
        destination: File,
        callback: Callback<File>
    ) {
        downloadArtifact(
            token, owner, repo, artifactId, destination,
            object : DownloadCallback<File> {
                override fun onProgress(downloadedBytes: Long, totalBytes: Long) {}
                override fun onSuccess(value: File) = callback.onSuccess(value)
                override fun onError(message: String) = callback.onError(message)
            }
        )
    }

    /**
     * النسخة الجديدة (مع تقدم).
     */
    @JvmStatic
    fun downloadArtifact(
        token: String,
        owner: String,
        repo: String,
        artifactId: Long,
        destination: File,
        callback: DownloadCallback<File>
    ) {
        run {
            var connection: HttpURLConnection? = null
            try {
                val url = "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                        "/actions/artifacts/$artifactId/zip"

                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 120000
                    readTimeout = 120000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", GitHubConfig.API_VERSION)
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    throw Exception("تعذر تنزيل artifact، HTTP $status")
                }

                var totalBytes = connection.contentLengthLong
                if (totalBytes <= 0) {
                    val lenHeader = connection.getHeaderField("Content-Length")
                    if (lenHeader != null) {
                        try {
                            totalBytes = lenHeader.toLong()
                        } catch (_: Exception) {
                        }
                    }
                }

                ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                    var entry: ZipEntry?
                    var extracted = false
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L

                    while (zip.nextEntry.also { entry = it } != null) {
                        if (!entry!!.isDirectory &&
                            entry!!.name.lowercase(Locale.US).endsWith(".apk")
                        ) {
                            destination.parentFile?.let { parent ->
                                if (!parent.exists()) parent.mkdirs()
                            }

                            FileOutputStream(destination).use { output ->
                                var count: Int
                                while (zip.read(buffer).also { count = it } != -1) {
                                    output.write(buffer, 0, count)
                                    downloadedBytes += count
                                    callback.onProgress(downloadedBytes, totalBytes)
                                }
                                output.flush()
                            }

                            extracted = true
                            break
                        }
                        zip.closeEntry()
                    }

                    if (!extracted) throw Exception("لم يوجد ملف APK داخل artifact")
                    callback.onSuccess(destination)
                }
            } catch (error: Exception) {
                callback.onError(message(error))
            } finally {
                connection?.disconnect()
            }
        }
    }

    // ============================================================
    // أدوات داخلية - HTTP
    // ============================================================
    private fun existingFileSha(
        token: String, owner: String, repo: String, branch: String, path: String
    ): String {
        val address = "${contentUrl(owner, repo, path)}?ref=${enc(branch)}"
        return try {
            val data = requestJson("GET", address, token, null, false)
            data.optString("sha", "")
        } catch (error: Exception) {
            val detail = message(error)
            if (detail.contains("GitHub HTTP 404")) ""
            else throw error
        }
    }

    private fun contentUrl(owner: String, repo: String, path: String): String {
        return "${GitHubConfig.API_URL}/repos/${encPath(owner)}/${encPath(repo)}" +
                "/contents/${path.replace(" ", "%20")}"
    }

    @Throws(Exception::class)
    private fun requireClientId() {
        if (GitHubConfig.CLIENT_ID.startsWith("REPLACE_")) {
            throw Exception("ضع GitHub OAuth Client ID في GitHubConfig أولًا")
        }
    }

    private fun run(task: Runnable) {
        try {
            EXECUTOR.execute(task)
        } catch (_: Exception) {
            Thread(task, "klencod-github-fallback").start()
        }
    }

    @Throws(Exception::class)
    private fun requestJson(
        method: String,
        address: String,
        token: String?,
        body: String?,
        form: Boolean
    ): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 120000
            readTimeout = 120000
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-GitHub-Api-Version", GitHubConfig.API_VERSION)
            if (token != null && token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    if (form) "application/x-www-form-urlencoded"
                    else "application/json"
                )
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val response = read(stream)

            if (status !in 200..299) {
                var detail = response
                try {
                    detail = JSONObject(response).optString("message", response)
                } catch (_: Exception) {
                }
                throw Exception("GitHub HTTP $status: $detail")
            }

            if (response.isNullOrBlank()) return JSONObject()
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun requestJsonArray(
        method: String,
        address: String,
        token: String?,
        body: String?,
        form: Boolean
    ): JSONArray {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 120000
            readTimeout = 120000
            doInput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", GitHubConfig.API_VERSION)
            if (token != null && token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    if (form) "application/x-www-form-urlencoded" else "application/json"
                )
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val response = read(stream)

            if (status !in 200..299) {
                var detail = response
                try {
                    detail = JSONObject(response).optString("message", response)
                } catch (_: Exception) {
                }
                throw Exception("GitHub HTTP $status: $detail")
            }

            if (response.isNullOrBlank()) return JSONArray()
            return JSONArray(response)
        } finally {
            connection.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun read(stream: InputStream?): String {
        if (stream == null) return ""
        val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
        val result = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            result.append(line)
        }
        reader.close()
        return result.toString()
    }

    @Throws(Exception::class)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encPath(value: String): String =
        value.replace(" ", "%20").replace("/", "%2F")

    private fun parseIsoTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            val pattern = if (iso.contains("."))
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            else
                "yyyy-MM-dd'T'HH:mm:ss'Z'"

            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(iso.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun message(error: Exception): String = AiErrorFormatter.format(error)

    // ============================================================
    // Zip helpers
    // ============================================================
    private fun isZipPayload(payload: ByteArray): Boolean {
        return payload.size >= 4 &&
                payload[0] == 'P'.code.toByte() &&
                payload[1] == 'K'.code.toByte() &&
                (payload[2] == 3.toByte() || payload[2] == 5.toByte() || payload[2] == 7.toByte()) &&
                (payload[3] == 4.toByte() || payload[3] == 6.toByte() || payload[3] == 8.toByte())
    }

    @Throws(Exception::class)
    private fun unzipJobLogs(payload: ByteArray): String {
        val logs = StringBuilder()
        ZipInputStream(BufferedInputStream(payload.inputStream())).use { zip ->
            var entry: ZipEntry?
            val buffer = ByteArray(8192)

            while (zip.nextEntry.also { entry = it } != null) {
                if (!entry!!.isDirectory) {
                    val entryOutput = ByteArrayOutputStream()
                    var count: Int
                    while (zip.read(buffer).also { count = it } != -1) {
                        entryOutput.write(buffer, 0, count)
                    }
                    val content = String(entryOutput.toByteArray(), StandardCharsets.UTF_8)
                    if (content.trim().isNotEmpty()) {
                        if (logs.isNotEmpty()) logs.append("\n")
                        logs.append(content)
                    }
                }
                zip.closeEntry()
            }
        }
        return logs.toString()
    }

    @Throws(Exception::class)
    private fun unzipRunLogs(payload: ByteArray): String {
        val logs = StringBuilder()
        ZipInputStream(BufferedInputStream(payload.inputStream())).use { zip ->
            var entry: ZipEntry?
            val buffer = ByteArray(8192)

            while (zip.nextEntry.also { entry = it } != null) {
                if (!entry!!.isDirectory) {
                    val entryOutput = ByteArrayOutputStream()
                    var count: Int
                    while (zip.read(buffer).also { count = it } != -1) {
                        entryOutput.write(buffer, 0, count)
                    }
                    val content = String(entryOutput.toByteArray(), StandardCharsets.UTF_8)
                    if (content.trim().isNotEmpty()) {
                        if (logs.isNotEmpty()) logs.append("\n\n")
                        logs.append("===== ").append(entry!!.name).append(" =====\n")
                        logs.append(content)
                    }
                }
                zip.closeEntry()
            }
        }
        if (logs.isEmpty()) throw Exception("أرشيف GitHub لا يحتوي على ملفات سجل نصية")
        return logs.toString().trim()
    }
}