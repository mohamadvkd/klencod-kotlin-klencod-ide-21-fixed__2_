package com.flutteride.codweva

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * واجهة البناء في IDE؛ كل عمليات GitHub تتم مباشرة من الهاتف.
 *
 * v3.1 — يدعم أنواع مشاريع متعددة:
 *   - Flutter: main.yml + app-release + inputs.flutter_version
 *   - Java:    build-android.yml + klencod-java-debug-apk (بدون inputs)
 *   - Kotlin:  build-android.yml + klencod-kotlin-debug-apk
 *   - React Native: build-android.yml + klencod-reactnative-release-apk (release)
 *
 * الميزات:
 *   - logMode للتصنيف بالألوان
 *   - detectQuotaFromLogs
 *   - extractAllLines / extractErrorLines
 *   - دعم DownloadCallback مع progress
 */
class FlutterBuildApi(private val context: Context) {

    private val appContext: Context = context.applicationContext

    var logMode: Int = 0

    // ============================================================
    // أنواع السجل
    // ============================================================
    companion object {
        const val LOG_TYPE_NORMAL = 0
        const val LOG_TYPE_SUCCESS = 1
        const val LOG_TYPE_WARNING = 2
        const val LOG_TYPE_ERROR = 3

        const val GITHUB_QUOTA_PREFIX = "__GITHUB_QUOTA__:"
        const val ANNOTATIONS_PREFIX = "__ANNOTATIONS__:"
        const val ANNOTATIONS_SEPARATOR = "__ANN_SEP__"

        const val WORKFLOW_FILE = "main.yml"
        const val ARTIFACT_NAME = "app-release"
        const val APK_NAME = "app-release.apk"

        private const val MAX_ALL_LOG_LINES = 10000
        private const val MAX_ERROR_LOG_LINES = 2000

        /**
         * تصنيف نص annotation رسمي من GitHub.
         */
        @JvmStatic
        fun classifyAnnotationStatic(annotation: String?): Int {
            if (annotation.isNullOrBlank()) return LOG_TYPE_NORMAL
            val lower = annotation.lowercase(Locale.US).trim()

            if (lower.startsWith("error:") || lower.startsWith("error ")) return LOG_TYPE_ERROR
            if (lower.contains("no file in") && lower.contains("matched to")) return LOG_TYPE_ERROR
            if (lower.contains("make sure you have checked out")) return LOG_TYPE_ERROR
            if (lower.contains("process completed with exit code")) return LOG_TYPE_ERROR
            if (lower.contains("build failed")) return LOG_TYPE_ERROR
            if (lower.contains("compilation failed")) return LOG_TYPE_ERROR
            if (lower.contains("command failed")) return LOG_TYPE_ERROR
            if (lower.contains("not found")) return LOG_TYPE_ERROR
            if (lower.contains("missing")) return LOG_TYPE_ERROR

            if (lower.startsWith("warning:") || lower.startsWith("warning ")) return LOG_TYPE_WARNING
            if (lower.contains("deprecated")) return LOG_TYPE_WARNING
            if (lower.contains("will no longer receive updates")) return LOG_TYPE_WARNING
            if (lower.contains("will migrate to")) return LOG_TYPE_WARNING
            if (lower.contains("node 20 is being deprecated")) return LOG_TYPE_WARNING

            return LOG_TYPE_NORMAL
        }
    }

    // ============================================================
    // BuildLogLine
    // ============================================================
    class BuildLogLine @JvmOverloads constructor(
        val text: String,
        val filePath: String?,
        val line: Int,
        val column: Int,
        val logType: Int = LOG_TYPE_NORMAL
    ) {
        val isError: Boolean = logType == LOG_TYPE_ERROR
    }

    // ============================================================
    // BuildCallback
    // ============================================================
    interface BuildCallback {
        fun onSuccess(apkFile: File)
        fun onProgress(message: String)
        fun onError(error: String)
        fun onStepsUpdate(steps: List<GitHubApi.Step>)
        fun onUploadProgress(current: Int, total: Int)
        fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long)
        fun onStepLogs(
            jobId: Long,
            allLogs: List<BuildLogLine>,
            errorLogs: List<BuildLogLine>
        )
        fun onRepositoryReady(repoName: String)
    }

    // ============================================================
    // البناء — النسخة القديمة (للتوافق)
    // ============================================================

    @Deprecated("Use buildApkDirect with projectType")
    fun buildApk(projectDir: File?, cacheDir: File?, callback: BuildCallback?) {
        buildApkDirect(
            projectDir,
            cacheDir,
            ProjectType.FLUTTER,
            callback
        )
    }

    @Deprecated("Use buildApkDirect with projectType")
    fun buildApkDirect(
        projectDir: File?,
        cacheDir: File?,
        callback: BuildCallback?
    ) {
        buildApkDirect(
            projectDir,
            cacheDir,
            ProjectType.FLUTTER,
            callback
        )
    }

    // ============================================================
    // البناء — النسخة الجديدة (تدعم projectType)
    // ============================================================

    /**
     * يبدأ عملية بناء APK على GitHub Actions.
     *
     * @param projectDir مجلد المشروع
     * @param cacheDir مجلد الكاش (لحفظ APK مؤقتاً)
     * @param projectType نوع المشروع (Flutter / Java / Kotlin / ReactNative)
     * @param callback الـ callbacks
     */
    fun buildApkDirect(
        projectDir: File?,
        cacheDir: File?,
        projectType: ProjectType,
        callback: BuildCallback?
    ) {
        // ============================================================
        // 1) التحقق من تسجيل الدخول
        // ============================================================
        val token = SecureStore(appContext).readGitHubToken()
        if (token.isBlank()) {
            callback?.onError("الرجاء ربط حساب GitHub أولًا")
            return
        }

        // ============================================================
        // 2) التحقق من مجلد المشروع
        // ============================================================
        if (projectDir == null || !projectDir.isDirectory) {
            callback?.onError("مجلد المشروع غير صالح")
            return
        }

        // ============================================================
        // 3) قراءة الإعدادات من prefs
        // ============================================================
        var customRepoName = ""
        var isPublicRepo = true
        var flutterVersion = "3.47.2"

        try {
            val prefs = appContext.getSharedPreferences("flutter_ide_prefs", Context.MODE_PRIVATE)
            customRepoName = prefs.getString("name_${projectDir.name}", "") ?: ""
            isPublicRepo = prefs.getBoolean("repo_public_${projectDir.name}", true)

            // Flutter version — يُقرأ فقط للـ Flutter
            if (projectType.isFlutter) {
                val savedVersion = prefs.getString("flutter_version_${projectDir.name}", "")
                if (!savedVersion.isNullOrBlank()) {
                    flutterVersion = savedVersion.trim()
                }
            }
        } catch (_: Exception) {
        }

        // ============================================================
        // 4) إشعار البدء
        // ============================================================
        val typeLabel = projectType.displayName
        callback?.onProgress("بدء عملية بناء APK ($typeLabel) عبر GitHub Actions...")

        // ============================================================
        // 5) النشر والبناء
        // ============================================================
        GitHubProjectPublisher.publish(
            token,
            projectDir,
            projectType,
            customRepoName,
            isPublicRepo,
            flutterVersion,
            object : GitHubProjectPublisher.Listener {
                override fun onProgress(message: String, progress: Int) {
                    // ============================================================
                    // معالجة خاصة لحالة الرفع
                    // ============================================================
                    if (message.startsWith("__UPLOAD__")) {
                        val payload = message.substring("__UPLOAD__".length)
                        val slash = payload.indexOf('/')
                        if (slash > 0) {
                            try {
                                val current = payload.substring(0, slash).toInt()
                                val total = payload.substring(slash + 1).toInt()
                                callback?.onUploadProgress(current, total)
                            } catch (_: NumberFormatException) {
                            }
                        }
                        return
                    }
                    callback?.onProgress("$message ($progress%)")
                }

                override fun onSuccess(
                    repository: GitHubApi.Repository,
                    run: GitHubApi.Run
                ) {
                    callback?.onRepositoryReady(repository.name)
                    callback?.onProgress("بدأت GitHub Actions رقم ${run.id}...")

                    // ============================================================
                    // بدء polling الحالة
                    // ============================================================
                    pollRun(
                        token,
                        repository,
                        run.id,
                        cacheDir,
                        projectType,
                        callback,
                        System.currentTimeMillis(),
                        0
                    )
                }

                override fun onError(message: String) {
                    callback?.onError(message.ifEmpty { "فشل نشر المشروع" })
                }
            }
        )
    }

    // ============================================================
    // Polling
    // ============================================================
    private fun pollRun(
        token: String,
        repository: GitHubApi.Repository,
        runId: Long,
        cacheDir: File?,
        projectType: ProjectType,
        callback: BuildCallback?,
        startedAt: Long,
        attempt: Int
    ) {
        GitHubApi.getRun(
            token, repository.owner, repository.name, runId,
            object : GitHubApi.Callback<GitHubApi.Run> {
                override fun onSuccess(run: GitHubApi.Run) {
                    // ============================================================
                    // مؤقت الوقت المنقضي
                    // ============================================================
                    val elapsed = (System.currentTimeMillis() - startedAt) / 1000L
                    val minutes = elapsed / 60L
                    val seconds = elapsed % 60L
                    val timer = "$minutes:" + String.format(Locale.US, "%02d", seconds)

                    // ============================================================
                    // جلب الخطوات
                    // ============================================================
                    GitHubApi.getRunJobs(
                        token, repository.owner, repository.name, runId,
                        object : GitHubApi.Callback<List<GitHubApi.Job>> {
                            override fun onSuccess(jobs: List<GitHubApi.Job>) {
                                if (jobs.isEmpty()) return
                                val job = jobs[0]
                                callback?.onStepsUpdate(job.steps)

                                if (job.status.equals("completed", ignoreCase = true)) {
                                    fetchStepLogs(token, repository, job, callback)
                                }
                            }

                            override fun onError(message: String) {
                                // نتجاهل ونكمل
                            }
                        }
                    )

                    callback?.onProgress("حالة GitHub: ${run.status} — الوقت $timer")

                    // ============================================================
                    // معالجة الاكتمال
                    // ============================================================
                    if (run.status.equals("completed", ignoreCase = true)) {
                        if (!run.conclusion.equals("success", ignoreCase = true)) {
                            // فشل
                            handleFailedRun(
                                token, repository, runId, run.conclusion, callback
                            )
                        } else {
                            // نجح — نزّل APK
                            downloadArtifact(
                                token, repository, cacheDir,
                                projectType,
                                callback, 0
                            )
                        }
                        return
                    }

                    // ============================================================
                    // timeout: 200 محاولة × 3 ثوان = 10 دقائق
                    // ============================================================
                    if (attempt >= 200) {
                        callback?.onError("انتهت مهلة انتظار GitHub Actions")
                        return
                    }

                    schedulePoll(
                        token, repository, runId, cacheDir,
                        projectType,
                        callback, startedAt, attempt + 1
                    )
                }

                override fun onError(message: String) {
                    if (attempt >= 5) {
                        callback?.onError("فشل متابعة GitHub Actions:\n$message")
                        return
                    }
                    schedulePoll(
                        token, repository, runId, cacheDir,
                        projectType,
                        callback, startedAt, attempt + 1
                    )
                }
            }
        )
    }

    private fun schedulePoll(
        token: String,
        repository: GitHubApi.Repository,
        runId: Long,
        cacheDir: File?,
        projectType: ProjectType,
        callback: BuildCallback?,
        startedAt: Long,
        nextAttempt: Int
    ) {
        Thread {
            try {
                Thread.sleep(3000L)
            } catch (_: InterruptedException) {
            }
            pollRun(
                token, repository, runId, cacheDir,
                projectType,
                callback, startedAt, nextAttempt
            )
        }.start()
    }

    private fun fetchStepLogs(
        token: String,
        repository: GitHubApi.Repository,
        job: GitHubApi.Job,
        callback: BuildCallback?
    ) {
        GitHubApi.downloadJobLogs(
            token, repository.owner, repository.name, job.id,
            object : GitHubApi.Callback<String> {
                override fun onSuccess(logs: String) {
                    val allLogs = extractAllLines(logs)
                    val errorLogs = extractErrorLines(logs)
                    callback?.onStepLogs(job.id, allLogs, errorLogs)
                }

                override fun onError(message: String) {
                    // نتجاهل
                }
            }
        )
    }

    // ============================================================
    // معالجة الفشل
    // ============================================================
    private fun handleFailedRun(
        token: String,
        repository: GitHubApi.Repository,
        runId: Long,
        conclusion: String,
        callback: BuildCallback?
    ) {
        if (callback == null) return

        GitHubApi.getRunJobs(
            token, repository.owner, repository.name, runId,
            object : GitHubApi.Callback<List<GitHubApi.Job>> {
                override fun onSuccess(jobs: List<GitHubApi.Job>) {
                    if (jobs.isEmpty()) {
                        fetchAnnotationsForQuota(
                            token, repository, runId, conclusion, callback
                        )
                        return
                    }
                    val job = jobs[0]
                    if (job.steps.isEmpty()) {
                        fetchAnnotationsForQuota(
                            token, repository, runId, conclusion, callback
                        )
                        return
                    }
                    val hasIncompleteStep = job.steps.any {
                        !it.status.equals("completed", ignoreCase = true)
                    }
                    if (hasIncompleteStep) {
                        fetchAnnotationsForQuota(
                            token, repository, runId, conclusion, callback
                        )
                        return
                    }
                    fetchFailureLogsAndAnnotations(
                        token, repository, runId, conclusion, callback
                    )
                }

                override fun onError(message: String) {
                    fetchFailureLogsAndAnnotations(
                        token, repository, runId, conclusion, callback
                    )
                }
            }
        )
    }

    private fun fetchAnnotationsForQuota(
        token: String,
        repository: GitHubApi.Repository,
        runId: Long,
        conclusion: String,
        callback: BuildCallback?
    ) {
        callback?.onProgress("جارٍ التحقق من سبب فشل GitHub Actions...")

        GitHubApi.getRunAnnotations(
            token, repository.owner, repository.name, runId,
            object : GitHubApi.Callback<List<String>> {
                override fun onSuccess(annotations: List<String>) {
                    val sb = StringBuilder()
                    var hasQuotaKeyword = false

                    for (msg in annotations) {
                        if (msg.isBlank()) continue
                        val lower = msg.lowercase(Locale.US)
                        if (lower.contains("job was not started") ||
                            lower.contains("recent account payments have failed") ||
                            lower.contains("spending limit needs to be increased")
                        ) {
                            hasQuotaKeyword = true
                        }
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(msg.trim())
                    }

                    if (hasQuotaKeyword) {
                        callback?.onError(GITHUB_QUOTA_PREFIX + sb.toString())
                    } else {
                        fetchFailureLogsAndAnnotations(
                            token, repository, runId, conclusion, callback
                        )
                    }
                }

                override fun onError(message: String) {
                    fetchFailureLogsAndAnnotations(
                        token, repository, runId, conclusion, callback
                    )
                }
            }
        )
    }

    private fun fetchFailureLogsAndAnnotations(
        token: String,
        repository: GitHubApi.Repository,
        runId: Long,
        conclusion: String,
        callback: BuildCallback?
    ) {
        callback?.onProgress("فشل البناء، جارٍ جلب سجل GitHub...")

        GitHubApi.downloadRunLogs(
            token, repository.owner, repository.name, runId,
            object : GitHubApi.Callback<String> {
                override fun onSuccess(logs: String) {
                    val quotaMessage = extractQuotaFromLogs(logs)
                    if (quotaMessage != null) {
                        callback?.onError(GITHUB_QUOTA_PREFIX + quotaMessage)
                        return
                    }

                    GitHubApi.getRunAnnotations(
                        token, repository.owner, repository.name, runId,
                        object : GitHubApi.Callback<List<String>> {
                            override fun onSuccess(annotations: List<String>) {
                                callback?.onProgress("===== سجل GitHub =====\n$logs")

                                if (annotations.isNotEmpty()) {
                                    val sb = StringBuilder()
                                    for (annot in annotations) {
                                        if (annot.isBlank()) continue
                                        if (sb.isNotEmpty()) {
                                            sb.append(ANNOTATIONS_SEPARATOR)
                                        }
                                        sb.append(annot.trim())
                                    }
                                    if (sb.isNotEmpty()) {
                                        callback?.onError(
                                            ANNOTATIONS_PREFIX + sb.toString()
                                        )
                                        return
                                    }
                                }
                                callback?.onError("فشل GitHub Actions: $conclusion")
                            }

                            override fun onError(message: String) {
                                callback?.onProgress("===== سجل GitHub =====\n$logs")
                                callback?.onError("فشل GitHub Actions: $conclusion")
                            }
                        }
                    )
                }

                override fun onError(message: String) {
                    callback?.onError("فشل البناء وتعذر جلب سجل GitHub:\n$message")
                }
            }
        )
    }

    private fun extractQuotaFromLogs(logs: String?): String? {
        if (logs.isNullOrEmpty()) return null
        val lower = logs.lowercase(Locale.US)

        val hasJobNotStarted = lower.contains("job was not started")
        val hasPaymentsFailed = lower.contains("recent account payments have failed")
        val hasSpendingLimit = lower.contains("spending limit needs to be increased")
        val isQuotaError = hasJobNotStarted && (hasPaymentsFailed || hasSpendingLimit)
        if (!isQuotaError) return null

        val lines = logs.split(Regex("\\r?\\n"))
        val indices = sortedSetOf<Int>()

        for (i in lines.indices) {
            val lineLower = lines[i].lowercase(Locale.US)
            if (lineLower.contains("job was not started") ||
                lineLower.contains("recent account payments") ||
                lineLower.contains("spending limit") ||
                lineLower.contains("billing")
            ) {
                for (j in maxOf(0, i - 1)..minOf(lines.size - 1, i + 2)) {
                    indices.add(j)
                }
            }
        }

        val sb = StringBuilder()
        for (idx in indices) {
            if (idx < 0 || idx >= lines.size) continue
            val raw = lines[idx]
            val clean = raw.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s*"), "").trim()
            if (clean.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(clean)
            if (sb.length > 800) break
        }

        return sb.toString().trim().ifEmpty { null }
    }

    // ============================================================
    // تنزيل APK
    // ============================================================

    /**
     * v3.1 — يستخدم artifactName حسب نوع المشروع.
     */
    private fun downloadArtifact(
        token: String,
        repository: GitHubApi.Repository,
        cacheDir: File?,
        projectType: ProjectType,
        callback: BuildCallback?,
        attempt: Int
    ) {
        callback?.onProgress("اكتمل البناء، جارٍ تنزيل APK من GitHub...")

        // ============================================================
        // اختيار اسم artifact وملف APK حسب النوع
        // ============================================================
        val artifactName = GitHubConfig.artifactNameFor(projectType)

        // ⭐ التعديل 1: إضافة REACT_NATIVE
        val apkFileName = when (projectType) {
            ProjectType.FLUTTER -> "app-release.apk"
            ProjectType.JAVA -> "app-debug.apk"
            ProjectType.KOTLIN -> "app-debug.apk"
            ProjectType.REACT_NATIVE -> "app-release.apk"
        }

        GitHubApi.latestArtifact(
            token, repository.owner, repository.name,
            artifactName,
            object : GitHubApi.Callback<GitHubApi.Artifact> {
                override fun onSuccess(artifact: GitHubApi.Artifact) {
                    if (artifact.expired) {
                        callback?.onError("Artifact منتهي الصلاحية")
                        return
                    }
                    val destination = File(cacheDir, "klencod-direct-$apkFileName")

                    GitHubApi.downloadArtifact(
                        token, repository.owner, repository.name,
                        artifact.id, destination,
                        object : GitHubApi.DownloadCallback<File> {
                            override fun onProgress(
                                downloadedBytes: Long,
                                totalBytes: Long
                            ) {
                                callback?.onDownloadProgress(downloadedBytes, totalBytes)
                            }

                            override fun onSuccess(apk: File) {
                                try {
                                    val downloads = Environment
                                        .getExternalStoragePublicDirectory(
                                            Environment.DIRECTORY_DOWNLOADS
                                        )
                                    if (!downloads.exists() && !downloads.mkdirs()) {
                                        throw IOException("تعذر إنشاء مجلد التنزيلات")
                                    }

                                    val stamp = SimpleDateFormat(
                                        "yyyyMMdd_HHmmss", Locale.getDefault()
                                    ).format(Date())

                                    // ⭐ التعديل 2: إضافة REACT_NATIVE
                                    val prefix = when (projectType) {
                                        ProjectType.FLUTTER -> "flutter"
                                        ProjectType.JAVA -> "java"
                                        ProjectType.KOTLIN -> "kotlin"
                                        ProjectType.REACT_NATIVE -> "reactnative"
                                    }

                                    val saved = File(
                                        downloads,
                                        "${prefix}_${safeName(repository.name)}_$stamp.apk"
                                    )
                                    copy(apk, saved)

                                    callback?.onProgress("تم حفظ APK في مجلد التنزيلات.")
                                    callback?.onSuccess(saved)
                                } catch (error: Exception) {
                                    callback?.onError("تعذر حفظ APK:\n${error.message}")
                                }
                            }

                            override fun onError(message: String) {
                                callback?.onError("تعذر تنزيل APK:\n$message")
                            }
                        }
                    )
                }

                override fun onError(message: String) {
                    if (attempt < 4) {
                        Thread {
                            try {
                                Thread.sleep(5000L)
                            } catch (_: InterruptedException) {
                            }
                            downloadArtifact(
                                token, repository, cacheDir,
                                projectType,
                                callback, attempt + 1
                            )
                        }.start()
                    } else {
                        callback?.onError("تعذر العثور على APK بعد نجاح البناء:\n$message")
                    }
                }
            }
        )
    }

    // ============================================================
    // أدوات مساعدة
    // ============================================================
    private fun safeName(value: String?): String {
        if (value.isNullOrBlank()) return "app"
        return value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    }

    @Throws(IOException::class)
    private fun copy(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
    }

    // ============================================================
    // تثبيت APK
    // ============================================================
    fun installApk(apkFile: File?) {
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(appContext, "ملف APK غير موجود", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val apkUri = FileProvider.getUriForFile(
                appContext,
                appContext.packageName + ".fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
                )
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(appContext.packageManager) != null) {
                appContext.startActivity(intent)
            } else {
                Toast.makeText(
                    appContext,
                    "لا يمكن تثبيت التطبيقات",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (error: Exception) {
            Toast.makeText(
                appContext,
                "خطأ في التثبيت: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // استخراج السطور
    // ============================================================
    private fun extractAllLines(logs: String?): List<BuildLogLine> {
        val result = mutableListOf<BuildLogLine>()
        if (logs.isNullOrBlank()) return result

        val locationPattern = Pattern.compile(
            "([^\\s]+\\.(?:dart|java|kt|kts|py|js|ts|tsx|jsx|xml|yaml|yml|json|gradle))[:(](\\d+)(?::(\\d+))?"
        )

        val lines = logs.split(Regex("\\r?\\n"))
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val clean = trimmed.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s*"), "")
            if (isAdminLine(clean)) continue

            var filePath: String? = null
            var lineNum = 0
            var colNum = 0

            val m = locationPattern.matcher(clean)
            if (m.find()) {
                filePath = m.group(1)
                try {
                    lineNum = m.group(2).toInt()
                    m.group(3)?.let { colNum = it.toInt() }
                } catch (_: NumberFormatException) {
                }
            }

            val logType = classifyLogLine(clean)
            result.add(BuildLogLine(clean, filePath, lineNum, colNum, logType))
            if (result.size >= MAX_ALL_LOG_LINES) break
        }
        return result
    }

    private fun extractErrorLines(logs: String?): List<BuildLogLine> {
        val result = mutableListOf<BuildLogLine>()
        if (logs.isNullOrBlank()) return result

        val lines = logs.split(Regex("\\r?\\n"))
        if (lines.isEmpty()) return result

        val matchedIndices = sortedSetOf<Int>()

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) continue
            if (isAdminLine(trimmed)) continue

            val logType = classifyLogLine(trimmed)
            if (logType == LOG_TYPE_ERROR || logType == LOG_TYPE_WARNING) {
                matchedIndices.add(i)
                for (j in maxOf(0, i - 2)..minOf(lines.size - 1, i + 2)) {
                    matchedIndices.add(j)
                }
            }
        }

        if (matchedIndices.isEmpty()) return result

        val locationPattern = Pattern.compile(
            "([^\\s]+\\.(?:dart|java|kt|kts|py|js|ts|tsx|jsx|xml|yaml|yml|json|gradle))[:(](\\d+)(?::(\\d+))?"
        )
        val seen = hashSetOf<String>()

        for (idx in matchedIndices) {
            if (idx < 0 || idx >= lines.size) continue
            val trimmed = lines[idx].trim()
            if (trimmed.isEmpty()) continue
            val clean = trimmed.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s*"), "")
            if (isAdminLine(clean)) continue

            val key = clean.lowercase(Locale.US)
            if (key in seen) continue
            seen.add(key)

            var filePath: String? = null
            var lineNum = 0
            var colNum = 0

            val m = locationPattern.matcher(clean)
            if (m.find()) {
                filePath = m.group(1)
                try {
                    lineNum = m.group(2).toInt()
                    m.group(3)?.let { colNum = it.toInt() }
                } catch (_: NumberFormatException) {
                }
            }

            val logType = classifyLogLine(clean)
            result.add(BuildLogLine(clean, filePath, lineNum, colNum, logType))
            if (result.size >= MAX_ERROR_LOG_LINES) break
        }
        return result
    }

    private fun isAdminLine(line: String?): Boolean {
        if (line.isNullOrEmpty()) return true
        val lower = line.lowercase(Locale.US)

        if (line.startsWith("##[") || line.startsWith("::")) return true
        if (lower.contains("current runner version")) return true
        if (lower.contains("hosted compute agent")) return true
        if (lower.startsWith("version:")) return true
        if (lower.startsWith("commit:")) return true
        if (lower.startsWith("build date:")) return true
        if (lower.startsWith("worker id:")) return true
        if (lower.startsWith("azure region:")) return true
        if (lower == "ubuntu") return true
        if (lower == "lts") return true
        if (lower.startsWith("image:")) return true
        if (lower.startsWith("image release:")) return true
        if (lower.startsWith("included software:")) return true
        if (lower.startsWith("contents:")) return true
        if (lower.startsWith("metadata:")) return true
        if (lower.startsWith("packages:")) return true
        if (lower.startsWith("secret source:")) return true
        if (lower.startsWith("cache mode:")) return true
        if (lower.contains("prepare workflow directory")) return true
        if (lower.contains("prepare all required actions")) return true
        if (lower.contains("getting action download info")) return true
        if (lower.contains("complete job name")) return true
        if (lower.startsWith("[command]")) return true
        if (lower.startsWith("with:")) return true
        if (lower.startsWith("env:")) return true
        if (lower.startsWith("shell:")) return true
        if (lower.startsWith("hint:")) return true
        if (lower.startsWith("ssh-")) return true
        if (lower.startsWith("allow-unsafe")) return true
        if (lower.startsWith("persist-credentials")) return true
        if (lower.startsWith("fetch-depth")) return true
        if (lower.startsWith("fetch-tags")) return true
        if (lower.startsWith("show-progress")) return true
        if (lower.startsWith("sparse-checkout")) return true
        if (lower.startsWith("set-safe-directory")) return true
        if (lower.contains("syncing repository")) return true
        if (lower.contains("working directory is")) return true
        if (lower.contains("temporarily overriding home")) return true
        if (lower.contains("deleting the contents")) return true
        if (lower.contains("initialized empty git repository")) return true
        if (lower.contains("node 20 is being deprecated")) return true
        if (lower.contains("node 24 by default")) return true
        if (lower.contains("actions_allow_use_unsecure")) return true
        if (lower.contains("terminate orphan process")) return true
        if (lower.contains("post job cleanup")) return true
        if (lower.contains("cleaning up orphan")) return true

        return false
    }

    private fun classifyLogLine(line: String?): Int {
        if (line == null) return LOG_TYPE_NORMAL
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return LOG_TYPE_NORMAL
        val lower = trimmed.lowercase(Locale.US)

        if (lower.startsWith("(node:")) return LOG_TYPE_NORMAL
        if (lower.contains("deprecationwarning")) return LOG_TYPE_NORMAL
        if (lower.contains("--trace-deprecation")) return LOG_TYPE_NORMAL
        if (lower.contains("use `node --trace")) return LOG_TYPE_NORMAL
        if (lower.contains("[dep0040]")) return LOG_TYPE_NORMAL
        if (lower.contains("[dep0169]")) return LOG_TYPE_NORMAL
        if (lower.contains("punycode")) return LOG_TYPE_NORMAL
        if (lower.contains("url.parse()")) return LOG_TYPE_NORMAL

        if (lower.startsWith("resolved java")) return LOG_TYPE_SUCCESS
        if (lower.startsWith("setting java")) return LOG_TYPE_SUCCESS
        if (lower.contains("\"install android sdk platform") && lower.contains("complete")) return LOG_TYPE_SUCCESS
        if (lower.contains("\"install android sdk platform") && lower.contains("finished")) return LOG_TYPE_SUCCESS
        if (lower.contains("install android sdk build-tools") && lower.contains("complete")) return LOG_TYPE_SUCCESS
        if (lower.startsWith("running gradle task")) return LOG_TYPE_SUCCESS
        if (lower.contains("artifact name is valid")) return LOG_TYPE_SUCCESS
        if (lower.contains("root directory input is valid")) return LOG_TYPE_SUCCESS
        if (lower.contains("beginning upload of artifact")) return LOG_TYPE_SUCCESS
        if (lower.startsWith("uploaded bytes")) return LOG_TYPE_SUCCESS
        if (lower.startsWith("sent ") && lower.contains("bytes")) return LOG_TYPE_SUCCESS
        if (lower.contains("with the provided path, there will be")) return LOG_TYPE_SUCCESS
        if (lower.contains("build succeeded")) return LOG_TYPE_SUCCESS
        if (lower.contains("built build/app")) return LOG_TYPE_SUCCESS
        if (lower.contains("apk generated")) return LOG_TYPE_SUCCESS
        if (lower.contains("✓ built")) return LOG_TYPE_SUCCESS

        if (lower.contains("font asset") && lower.contains("tree-shaken")) return LOG_TYPE_WARNING
        if (lower.contains("tree-shaking can be disabled")) return LOG_TYPE_WARNING
        if (lower.contains("--no-tree-shake-icons")) return LOG_TYPE_WARNING
        if (lower.contains("unable to find directory entry in pubspec.yaml")) return LOG_TYPE_WARNING
        if (lower.contains("warning:") && !lower.contains("0 warning")) return LOG_TYPE_WARNING
        if (lower.contains("warn:") && !lower.contains("0 warning")) return LOG_TYPE_WARNING
        if (lower.startsWith("warning:")) return LOG_TYPE_WARNING
        if (lower.contains("deprecated") && !lower.contains("deprecationwarning")) return LOG_TYPE_WARNING
        if (lower.contains("only the first match will be used")) return LOG_TYPE_WARNING

        if (lower.contains("0 warning") || lower.contains("warnings: 0")) return LOG_TYPE_SUCCESS
        if (lower.contains("0 error") || lower.contains("errors: 0")) return LOG_TYPE_SUCCESS

        if (lower.contains("error:") || lower.startsWith("error")) return LOG_TYPE_ERROR
        if (lower.contains("build failed")) return LOG_TYPE_ERROR
        if (lower.contains("compilation failed")) return LOG_TYPE_ERROR
        if (lower.contains("execution failed")) return LOG_TYPE_ERROR
        if (lower.contains("a problem occurred")) return LOG_TYPE_ERROR
        if (lower.contains("what went wrong")) return LOG_TYPE_ERROR
        if (lower.contains("fatal:")) return LOG_TYPE_ERROR
        if (lower.contains("critical:")) return LOG_TYPE_ERROR
        if (lower.contains("exception:")) return LOG_TYPE_ERROR
        if (lower.contains("could not resolve")) return LOG_TYPE_ERROR
        if (lower.contains("could not find")) return LOG_TYPE_ERROR
        if (lower.contains("could not download")) return LOG_TYPE_ERROR
        if (lower.contains("could not create")) return LOG_TYPE_ERROR
        if (lower.contains("could not determine")) return LOG_TYPE_ERROR
        if (lower.contains("cannot find")) return LOG_TYPE_ERROR
        if (lower.contains("cannot resolve")) return LOG_TYPE_ERROR
        if (lower.contains("unable to load")) return LOG_TYPE_ERROR
        if (lower.contains("undefined name")) return LOG_TYPE_ERROR
        if (lower.contains("isn't defined")) return LOG_TYPE_ERROR
        if (lower.contains("no such file")) return LOG_TYPE_ERROR
        if (lower.contains("unhandled exception")) return LOG_TYPE_ERROR
        if (lower.contains("formatexception")) return LOG_TYPE_ERROR
        if (lower.contains("stateerror")) return LOG_TYPE_ERROR
        if (lower.contains("assertionerror")) return LOG_TYPE_ERROR
        if (lower.contains("indexerror")) return LOG_TYPE_ERROR
        if (lower.contains("rangeerror")) return LOG_TYPE_ERROR
        if (lower.contains("nosuchmethoderror")) return LOG_TYPE_ERROR
        if (lower.contains("caused by")) return LOG_TYPE_ERROR
        if (lower.contains("rejected")) return LOG_TYPE_ERROR
        if (lower.contains("failed")) return LOG_TYPE_ERROR
        if (lower.contains("failure")) return LOG_TYPE_ERROR
        if (lower.contains("fatal")) return LOG_TYPE_ERROR

        return LOG_TYPE_NORMAL
    }
}