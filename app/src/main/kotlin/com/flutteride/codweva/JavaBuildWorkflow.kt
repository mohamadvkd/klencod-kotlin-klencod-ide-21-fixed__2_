package com.flutteride.codweva.java

/**
 * JavaBuildWorkflow — يولّد محتوى `.github/workflows/build-android.yml`
 * لمشاريع Android Java التي تُبنى عبر GitHub Actions.
 *
 * v2.0 — إصلاح مشكلة sdkmanager:
 *   - لا نستخدم gradle/actions/setup-gradle (نستخدم gradlew من المشروع)
 *   - نستخدم android-actions/setup-android فقط
 *   - نثبّت platform واحد فقط (بدون platform-tools)
 *   - لا نستخدم packages متعددة في sdkmanager
 */
object JavaBuildWorkflow {

    @JvmStatic
    fun build(targetSdk: Int): String {
        val safeTargetSdk = targetSdk.coerceIn(21, 36)
        return """
            |name: Build Android Java APK
            |
            |on:
            |  workflow_dispatch:
            |
            |permissions:
            |  contents: read
            |
            |concurrency:
            |  group: klencod-java-${'$'}{{ github.repository }}
            |  cancel-in-progress: true
            |
            |jobs:
            |  build:
            |    name: Build debug APK
            |    runs-on: ubuntu-latest
            |    timeout-minutes: 30
            |
            |    steps:
            |      - name: Checkout project
            |        uses: actions/checkout@v4
            |        with:
            |          fetch-depth: 1
            |
            |      - name: Set up Java 17
            |        uses: actions/setup-java@v4
            |        with:
            |          distribution: temurin
            |          java-version: '17'
            |
            |      - name: Set up Android SDK
            |        uses: android-actions/setup-android@v3
            |        with:
            |          packages: 'platforms;android-34 build-tools;34.0.0'
            |          accept-android-sdk-licenses: true
            |
            |      - name: Build debug APK
            |        shell: bash
            |        run: |
            |          set -o pipefail
            |          chmod +x gradlew || true
            |          ./gradlew --no-daemon --build-cache --parallel :app:assembleDebug -x lint -x test 2>&1 | tee "${'$'}RUNNER_TEMP/klencod-gradle.log"
            |
            |      - name: Print compiler diagnostics on failure
            |        if: failure()
            |        shell: bash
            |        run: |
            |          log="${'$'}RUNNER_TEMP/klencod-gradle.log"
            |          echo "===== Klencod compiler diagnostics ====="
            |          if [[ -f "${'$'}log" ]]; then
            |            grep -E 'error:|warning:|FAILED|Compilation failed|BUILD FAILED' "${'$'}log" | tail -n 120 || true
            |          else
            |            echo "Gradle output file was not created."
            |          fi
            |
            |      - name: Upload APK artifact
            |        uses: actions/upload-artifact@v4
            |        with:
            |          name: klencod-java-debug-apk
            |          path: app/build/outputs/apk/debug/*.apk
            |          if-no-files-found: error
            |          retention-days: 7
        """.trimMargin() + "\n"
    }

    @JvmStatic
    fun preview(): String = build(34)

    @JvmStatic
    fun hasWorkflowDispatch(content: String?): Boolean {
        if (content.isNullOrEmpty()) return false
        val pattern = Regex("^\\s*workflow_dispatch\\s*:", RegexOption.MULTILINE)
        return pattern.containsMatchIn(content)
    }
}