package com.flutteride.codweva

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/**
 * GitHubProjectPublisher — ينسّق نشر مشروع وتشغيل GitHub Actions.
 *
 * v2.0 — يدعم أنواع مشاريع متعددة:
 *   - Flutter: main.yml + app-release + inputs.flutter_version
 *   - Java:    build-android.yml + klencod-java-debug-apk (بدون inputs)
 *   - Kotlin:  (لاحقاً)
 *
 * التغييرات:
 *   - publish يستقبل projectType
 *   - repositoryName يستخدم البادئة حسب النوع
 *   - triggerBuild يستخدم workflowFileFor
 *   - latestRun يستخدم workflowFileFor
 *   - التوافق مع Flutter محفوظ عبر overload default
 */
object GitHubProjectPublisher {

    interface Listener {
        fun onProgress(message: String, progress: Int)
        fun onSuccess(repository: GitHubApi.Repository, run: GitHubApi.Run)
        fun onError(message: String)
    }

    // ============================================================
    // النسخة القديمة — Flutter افتراضياً
    // ============================================================
    @Deprecated("Use version with projectType")
    @JvmStatic
    fun publish(
        token: String,
        project: File,
        customRepoName: String?,
        isPublicRepo: Boolean,
        listener: Listener
    ) {
        publish(
            token, project, ProjectType.FLUTTER,
            customRepoName, isPublicRepo, "3.47.2", listener
        )
    }

    @Deprecated("Use version with projectType")
    @JvmStatic
    fun publish(
        token: String?,
        project: File?,
        customRepoName: String?,
        isPublicRepo: Boolean,
        flutterVersion: String?,
        listener: Listener
    ) {
        publish(
            token, project, ProjectType.FLUTTER,
            customRepoName, isPublicRepo, flutterVersion, listener
        )
    }

    // ============================================================
    // النسخة الجديدة — تقبل projectType
    // ============================================================
    @JvmStatic
    fun publish(
        token: String?,
        project: File?,
        projectType: ProjectType,
        customRepoName: String?,
        isPublicRepo: Boolean,
        flutterVersion: String?,
        listener: Listener
    ) {
        // ============================================================
        // 1) التحقق من المدخلات
        // ============================================================
        if (token.isNullOrBlank()) {
            listener.onError("اربط حساب GitHub أولًا")
            return
        }
        if (project == null || !project.isDirectory) {
            listener.onError("مجلد المشروع غير صالح")
            return
        }

        // ============================================================
        // 2) الإعدادات
        // ============================================================
        val version = if (flutterVersion.isNullOrBlank()) "3.47.2" else flutterVersion.trim()
        val publishStartedAtMs = System.currentTimeMillis()
        val typeLabel = projectType.displayName

        listener.onProgress("التحقق من حساب GitHub لمشروع $typeLabel...", 5)

        // ============================================================
        // 3) التحقق من الحساب
        // ============================================================
        GitHubApi.getUser(token, object : GitHubApi.Callback<String> {
            override fun onSuccess(owner: String) {
                val rawName = if (!customRepoName.isNullOrBlank()) customRepoName else project.name
                val repoName = repositoryName(rawName, projectType)

                // ============================================================
                // 4) البحث عن المستودع أو إنشاؤه
                // ============================================================
                GitHubApi.getRepository(
                    token, owner, repoName,
                    object : GitHubApi.Callback<GitHubApi.Repository> {
                        override fun onSuccess(repository: GitHubApi.Repository) {
                            listener.onProgress("تم العثور على المستودع، جارٍ تحديث الملفات...", 18)
                            uploadFiles(
                                token, project, repository, projectType,
                                publishStartedAtMs, version, listener
                            )
                        }

                        override fun onError(message: String) {
                            if (message.contains("GitHub HTTP 404")) {
                                createRepository(
                                    token, repoName, project, isPublicRepo, projectType,
                                    publishStartedAtMs, version, listener
                                )
                            } else {
                                listener.onError("فشل الوصول للمستودع:\n$message")
                            }
                        }
                    }
                )
            }

            override fun onError(message: String) {
                listener.onError("فشل التعرف على الحساب:\n$message")
            }
        })
    }

    // ============================================================
    // إنشاء المستودع
    // ============================================================
    private fun createRepository(
        token: String,
        name: String,
        project: File,
        isPublicRepo: Boolean,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        val typeLabel = if (isPublicRepo) "عام" else "خاص"
        listener.onProgress("لم يوجد المستودع، جارٍ إنشاؤه ($typeLabel)...", 10)

        createRepositoryWithRetry(
            token, name, 0, project, isPublicRepo, projectType,
            publishStartedAtMs, flutterVersion, listener
        )
    }

    private fun createRepositoryWithRetry(
        token: String,
        baseName: String,
        suffix: Int,
        project: File,
        isPublicRepo: Boolean,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        if (suffix > 50) {
            listener.onError("تعذر إيجاد اسم مستودع متاح (جرب 50 اسماً)")
            return
        }

        val candidate = if (suffix == 0) baseName else "${baseName}_$suffix"

        if (suffix > 0) {
            listener.onProgress("جارٍ تجربة الاسم: \"$candidate\"...", 12)
        }

        GitHubApi.createRepository(
            token, candidate, !isPublicRepo,
            object : GitHubApi.Callback<GitHubApi.Repository> {
                override fun onSuccess(repository: GitHubApi.Repository) {
                    val typeLabel = if (isPublicRepo) "عام" else "خاص"
                    if (suffix > 0) {
                        listener.onProgress(
                            "الاسم \"$baseName\" كان مستخدماً، تم استخدام \"$candidate\" ($typeLabel)",
                            20
                        )
                    } else {
                        listener.onProgress(
                            "تم إنشاء المستودع \"$candidate\" ($typeLabel) بنجاح",
                            20
                        )
                    }
                    uploadFiles(
                        token, project, repository, projectType,
                        publishStartedAtMs, flutterVersion, listener
                    )
                }

                override fun onError(message: String) {
                    if (isNameConflict(message)) {
                        createRepositoryWithRetry(
                            token, baseName, suffix + 1, project, isPublicRepo, projectType,
                            publishStartedAtMs, flutterVersion, listener
                        )
                    } else {
                        listener.onError("فشل إنشاء المستودع:\n$message")
                    }
                }
            }
        )
    }

    private fun isNameConflict(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase(Locale.US)
        return lower.contains("http 422") ||
                lower.contains("already exists") ||
                lower.contains("name already") ||
                lower.contains("repository creation failed") ||
                lower.contains("validation failed") ||
                lower.contains("name is not available")
    }

    // ============================================================
    // رفع الملفات
    // ============================================================
    private fun uploadFiles(
        token: String,
        project: File,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        val files = mutableListOf<File>()
        collect(project, project, files)

        if (files.isEmpty()) {
            listener.onError("لا توجد ملفات قابلة للرفع داخل المشروع")
            return
        }

        files.sortWith(compareBy { relative(project, it) })
        uploadNext(
            token, project, repository, projectType, files, 0,
            publishStartedAtMs, flutterVersion, listener
        )
    }

    private fun uploadNext(
        token: String,
        project: File,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        files: List<File>,
        index: Int,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        if (index >= files.size) {
            listener.onProgress("اكتمل رفع الملفات، جارٍ تشغيل GitHub Actions...", 82)

            // ⭐ استخدام workflowFileFor حسب نوع المشروع
            val workflowFile = GitHubConfig.workflowFileFor(projectType)

            GitHubApi.triggerBuild(
                token, repository.owner, repository.name, repository.defaultBranch,
                workflowFile,              // ⭐ NEW
                flutterVersion,            // ⭐ يُستخدم فقط لـ Flutter
                object : GitHubApi.Callback<Boolean> {
                    override fun onSuccess(value: Boolean) {
                        listener.onProgress(
                            "تم تشغيل GitHub Actions، جارٍ انتظار البناء...",
                            85
                        )
                        findStartedRun(
                            token, repository, projectType,
                            publishStartedAtMs, listener, 0
                        )
                    }

                    override fun onError(message: String) {
                        listener.onError("فشل تشغيل GitHub Actions:\n$message")
                    }
                }
            )
            return
        }

        val file = files[index]
        val path = relative(project, file)
        val progress = 25 + (index * 55 / maxOf(1, files.size))

        listener.onProgress("__UPLOAD__${index + 1}/${files.size}", progress)

        Thread {
            try {
                val bytes = readBytes(file)
                GitHubApi.uploadBytes(
                    token, repository.owner, repository.name, repository.defaultBranch,
                    path, bytes, "Update $path",
                    object : GitHubApi.Callback<Boolean> {
                        override fun onSuccess(value: Boolean) {
                            uploadNext(
                                token, project, repository, projectType, files, index + 1,
                                publishStartedAtMs, flutterVersion, listener
                            )
                        }

                        override fun onError(message: String) {
                            listener.onError("فشل رفع \"$path\":\n$message")
                        }
                    }
                )
            } catch (error: Exception) {
                listener.onError("تعذر قراءة \"$path\":\n${error.message}")
            }
        }.start()
    }

    // ============================================================
    // انتظار بدء الـ Run
    // ============================================================
    private fun findStartedRun(
        token: String,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        listener: Listener,
        attempt: Int
    ) {
        // ⭐ استخدام workflowFileFor
        val workflowFile = GitHubConfig.workflowFileFor(projectType)

        GitHubApi.latestRun(
            token, repository.owner, repository.name, publishStartedAtMs,
            workflowFile,                  // ⭐ NEW
            object : GitHubApi.Callback<GitHubApi.Run> {
                override fun onSuccess(run: GitHubApi.Run) {
                    listener.onSuccess(repository, run)
                }

                override fun onError(message: String) {
                    if (attempt >= 8) {
                        listener.onError(
                            "تم تشغيل البناء، لكن تعذر الحصول على رقم العملية:\n$message"
                        )
                        return
                    }
                    Thread {
                        try {
                            Thread.sleep(2500L)
                        } catch (_: InterruptedException) {
                        }
                        findStartedRun(
                            token, repository, projectType,
                            publishStartedAtMs, listener, attempt + 1
                        )
                    }.start()
                }
            }
        )
    }

    // ============================================================
    // الأدوات
    // ============================================================
    private fun collect(root: File, directory: File, result: MutableList<File>) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            val path = relative(root, child)
            if (isExcluded(path)) continue
            if (child.isDirectory) {
                collect(root, child, result)
            } else if (child.isFile && child.length() <= 10L * 1024L * 1024L) {
                result.add(child)
            }
        }
    }

    private fun isExcluded(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val lower = normalized.lowercase(Locale.US)
        return lower == "local.properties" ||
                lower.startsWith(".git/") ||
                lower.startsWith(".klencod_backup/") ||
                lower.startsWith(".klencod/") ||
                lower.startsWith("build/") ||
                lower.contains("/build/") ||
                lower.contains(".gradle/") ||
                lower.contains("keystore") ||
                lower.endsWith(".jks") ||
                lower.endsWith(".keystore") ||
                lower.endsWith(".pem") ||
                lower.endsWith(".apk") ||
                lower.endsWith(".aab")
    }

    @Throws(IOException::class)
    private fun readBytes(file: File): ByteArray {
        FileInputStream(file).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }
    }

    private fun relative(root: File, file: File): String {
        return try {
            val base = root.canonicalPath
            val value = file.canonicalPath
            if (value.startsWith("$base${File.separator}")) {
                value.substring(base.length + 1).replace(File.separatorChar, '/')
            } else {
                file.name
            }
        } catch (_: IOException) {
            file.name
        }
    }

    // ============================================================
    // اسم المستودع
    // ============================================================

    /**
     * يولّد اسم مستودع صالحاً.
     *
     * v2.0 — يستخدم البادئة حسب نوع المشروع.
     */
    private fun repositoryName(raw: String?, projectType: ProjectType): String {
        var name = raw?.trim() ?: "app"
        if (name.isEmpty()) name = "app"

        val sb = StringBuilder()
        for (c in name) {
            val isArabic = (c in '\u0600'..'\u06FF') ||
                    (c in '\u0750'..'\u077F') ||
                    (c in '\uFB50'..'\uFDFF') ||
                    (c in '\uFE70'..'\uFEFF')
            val isLatinDigit = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9')
            val isSafe = c == '-' || c == '_' || c == '.'

            when {
                isArabic || isLatinDigit || isSafe -> sb.append(c)
                c == ' ' -> sb.append('-')
            }
        }

        name = sb.toString()
        while (name.startsWith("-") || name.startsWith(".")) name = name.substring(1)
        while (name.endsWith("-") || name.endsWith(".")) {
            name = name.substring(0, name.length - 1)
        }
        if (name.isEmpty()) name = "app"

        // ⭐ إضافة بادئة حسب النوع
        val prefix = GitHubConfig.repoPrefixFor(projectType)

        // تجنب التكرار: إذا الاسم يبدأ بالبادئة، لا نضيفها مرة أخرى
        val finalName = if (name.startsWith(prefix)) {
            name
        } else {
            "$prefix$name"
        }

        // تحديد الحد الأقصى لطول اسم المستودع (100 حرف على GitHub)
        return if (finalName.length > 90) finalName.substring(0, 90) else finalName
    }
}