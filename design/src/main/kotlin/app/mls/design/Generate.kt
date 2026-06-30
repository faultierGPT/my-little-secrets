package app.mls.design

import java.io.File

/**
 * Regenerates every per-toolkit artifact from the canonical [MlsTokens]. Run via `./gradlew :design:run`
 * (working dir = the design module) or `./gradlew :design:run --args="<outDir>"`. The outputs are
 * committed so the clients can consume them without running this first; this just keeps them in sync.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrNull(0) ?: "generated")
    val composePath = "compose/" + ComposeThemeRenderer.PACKAGE.replace('.', '/') + "/MlsDesignTokens.kt"
    val javaPath = "java/" + JavaConstantsRenderer.PACKAGE.replace('.', '/') + "/MlsTokens.java"

    write(File(outDir, "tokens.json"), TokensJsonRenderer.render())
    write(File(outDir, composePath), ComposeThemeRenderer.render())
    write(File(outDir, "javafx/mls-theme.css"), JavaFxCssRenderer.render())
    write(File(outDir, javaPath), JavaConstantsRenderer.render())
    println("Design tokens v${MlsTokens.VERSION} written to ${outDir.absolutePath}")
}

private fun write(file: File, content: String) {
    file.parentFile?.mkdirs()
    file.writeText(if (content.endsWith("\n")) content else content + "\n")
    println("  ${file.path} (${content.length} chars)")
}
