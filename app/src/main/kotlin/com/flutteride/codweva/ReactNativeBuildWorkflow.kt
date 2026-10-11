package com.flutteride.codweva.reactnative

/**
 * ReactNativeBuildWorkflow — يولّد محتوى `.github/workflows/build-android.yml`
 * لمشاريع React Native CLI.
 *
 * v1.0 — مبني على workflow الجاهز من KLENCOD-RN:
 *   - Node 20 + Java 17
 *   - Gradle 8.10.2
 *   - verification steps إضافية
 *   - debug.keystore generation
 *   - rفع APK كـ artifact
 *
 * الـ artifactName: klencod-reactnative-release-apk
 */
object ReactNativeBuildWorkflow {

    /** اسم الـ artifact على GitHub */
    const val ARTIFACT_NAME = "klencod-reactnative-release-apk"

    /** اسم ملف الـ workflow */
    const val WORKFLOW_FILE = "build-android.yml"

    // ============================================================
    // الدالة الرئيسية
    // ============================================================
    @JvmStatic
    fun build(targetSdk: Int): String {
        val safeTargetSdk = targetSdk.coerceIn(21, 36)
        val sb = StringBuilder(4096)

        sb.append("name: Build React Native APK\n")
        sb.append("\n")
        sb.append("on:\n")
        sb.append("  workflow_dispatch:\n")
        sb.append("\n")
        sb.append("permissions:\n")
        sb.append("  contents: read\n")
        sb.append("\n")
        sb.append("concurrency:\n")
        sb.append("  group: klencod-rn-\${{ github.repository }}\n")
        sb.append("  cancel-in-progress: true\n")
        sb.append("\n")
        sb.append("jobs:\n")
        sb.append("  build:\n")
        sb.append("    name: Build release APK\n")
        sb.append("    runs-on: ubuntu-latest\n")
        sb.append("    timeout-minutes: 45\n")
        sb.append("\n")
        sb.append("    steps:\n")
        sb.append("      - name: Checkout\n")
        sb.append("        uses: actions/checkout@v4\n")
        sb.append("        with:\n")
        sb.append("          fetch-depth: 1\n")
        sb.append("\n")
        sb.append("      - name: Setup Node.js\n")
        sb.append("        uses: actions/setup-node@v4\n")
        sb.append("        with:\n")
        sb.append("          node-version: 20\n")
        sb.append("\n")
        sb.append("      - name: Setup Java 17\n")
        sb.append("        uses: actions/setup-java@v4\n")
        sb.append("        with:\n")
        sb.append("          distribution: temurin\n")
        sb.append("          java-version: '17'\n")
        sb.append("\n")
        sb.append("      - name: Setup Gradle\n")
        sb.append("        uses: gradle/actions/setup-gradle@v4\n")
        sb.append("        with:\n")
        sb.append("          gradle-version: 8.10.2\n")
        sb.append("\n")
        sb.append("      - name: Install npm dependencies\n")
        sb.append("        shell: bash\n")
        sb.append("        run: |\n")
        sb.append("          npm install --no-audit --no-fund\n")
        sb.append("          npm install --save-dev --no-audit --no-fund \\\n")
        sb.append("            @react-native-community/cli@15.0.1 \\\n")
        sb.append("            @react-native-community/cli-platform-android@15.0.1 \\\n")
        sb.append("            @react-native-community/cli-platform-ios@15.0.1\n")
        sb.append("\n")
        sb.append("      - name: Verify native Android template\n")
        sb.append("        shell: bash\n")
        sb.append("        run: |\n")
        sb.append("          set -euo pipefail\n")
        sb.append("          APP_ID=\$(node -e \"const a=require('./app.json'); const n=(a.name||'app').toLowerCase().replace(/[^a-z0-9]+/g,'_'); console.log((a.android&&a.android.packageName)||('com.example.'+n));\")\n")
        sb.append("          APP_PATH=\$(printf '%s' \"\$APP_ID\" | tr '.' '/')\n")
        sb.append("          test -f android/settings.gradle\n")
        sb.append("          test -f android/build.gradle\n")
        sb.append("          test -f android/app/build.gradle\n")
        sb.append("          test -f android/app/src/main/AndroidManifest.xml\n")
        sb.append("          test -f \"android/app/src/main/java/\$APP_PATH/MainActivity.kt\" || test -f \"android/app/src/main/java/\$APP_PATH/MainActivity.java\"\n")
        sb.append("          test -f \"android/app/src/main/java/\$APP_PATH/MainApplication.kt\" || test -f \"android/app/src/main/java/\$APP_PATH/MainApplication.java\"\n")
        sb.append("          test -f \"android/app/src/main/java/\$APP_PATH/CrashLogger.java\"\n")
        sb.append("          test -f \"android/app/src/main/java/\$APP_PATH/AppLoggerPackage.java\"\n")
        sb.append("          grep -R \"CrashLogger.install\" \"android/app/src/main/java/\$APP_PATH\"\n")
        sb.append("          grep -R \"add(AppLoggerPackage())\" \"android/app/src/main/java/\$APP_PATH\"\n")
        sb.append("          echo \"Native Android template verified.\"\n")
        sb.append("\n")
        sb.append("      - name: Prepare debug signing key\n")
        sb.append("        working-directory: android/app\n")
        sb.append("        shell: bash\n")
        sb.append("        run: |\n")
        sb.append("          if [ ! -f debug.keystore ]; then\n")
        sb.append("            keytool -genkeypair -v \\\n")
        sb.append("              -keystore debug.keystore \\\n")
        sb.append("              -storepass android \\\n")
        sb.append("              -alias androiddebugkey \\\n")
        sb.append("              -keypass android \\\n")
        sb.append("              -keyalg RSA \\\n")
        sb.append("              -keysize 2048 \\\n")
        sb.append("              -validity 10000 \\\n")
        sb.append("              -dname \"CN=Android Debug,O=Android,C=US\"\n")
        sb.append("          fi\n")
        sb.append("\n")
        sb.append("      - name: Build release APK\n")
        sb.append("        shell: bash\n")
        sb.append("        run: |\n")
        sb.append("          set -o pipefail\n")
        sb.append("          gradle -p android assembleRelease --no-daemon --build-cache 2>&1 | tee \"\$RUNNER_TEMP/klencod-rn-gradle.log\"\n")
        sb.append("\n")
        sb.append("      - name: Print compiler diagnostics on failure\n")
        sb.append("        if: failure()\n")
        sb.append("        shell: bash\n")
        sb.append("        run: |\n")
        sb.append("          log=\"\$RUNNER_TEMP/klencod-rn-gradle.log\"\n")
        sb.append("          echo \"===== Klencod React Native diagnostics =====\"\n")
        sb.append("          if [[ -f \"\$log\" ]]; then\n")
        sb.append("            grep -E 'error:|warning:|FAILED|Compilation failed|BUILD FAILED|What went wrong' \"\$log\" | tail -n 150 || true\n")
        sb.append("          else\n")
        sb.append("            echo \"Gradle output file was not created.\"\n")
        sb.append("          fi\n")
        sb.append("\n")
        sb.append("      - name: Upload APK artifact\n")
        sb.append("        uses: actions/upload-artifact@v4\n")
        sb.append("        with:\n")
        sb.append("          name: $ARTIFACT_NAME\n")
        sb.append("          path: android/app/build/outputs/apk/release/app-release.apk\n")
        sb.append("          if-no-files-found: error\n")
        sb.append("          retention-days: 7\n")

        return sb.toString()
    }

    // ============================================================
    // نسخة مختصرة للاختبار
    // ============================================================
    @JvmStatic
    fun preview(): String = build(34)

    // ============================================================
    // تحقق
    // ============================================================
    @JvmStatic
    fun hasWorkflowDispatch(content: String?): Boolean {
        if (content.isNullOrEmpty()) return false
        val pattern = Regex("^\\s*workflow_dispatch\\s*:", RegexOption.MULTILINE)
        return pattern.containsMatchIn(content)
    }
}