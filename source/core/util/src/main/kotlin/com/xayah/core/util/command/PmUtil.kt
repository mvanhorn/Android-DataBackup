package com.xayah.core.util.command

import android.os.Build
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.model.ShellResult

object Pm {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("pm", *args)

    // stat -c %s "$src" from the privileged shell, so a root-owned extract is visible.
    // Failure is returned unchanged and must not be rewritten into `pm install -S`.
    private suspend fun apkByteCount(src: String): ShellResult = BaseUtil.execute(
        "stat",
        "-c",
        "%s",
        "$QUOTE$src$QUOTE",
    )

    // Pre-R setParamsSize/parseApkLite opens the pathname and cannot read app-private files.
    // Stream the bytes instead. `--` keeps `-` as stdin; ShellCommand treats a bare `-` as an option.
    // pm install --user "$userId" -r -t -S "$bytes" -- - < "$src"
    private suspend fun installFromStream(userId: Int, src: String): ShellResult {
        val sizeResult = apkByteCount(src)
        if (sizeResult.isSuccess.not()) return sizeResult
        val sizeText = sizeResult.outString.trim()
        val size = sizeText.toLongOrNull()
        if (size == null || size <= 0L) {
            return ShellResult(
                code = -1,
                input = sizeResult.input,
                out = sizeResult.out + "Invalid APK byte count: $sizeText",
            )
        }
        return execute(
            "install",
            "--user",
            "$QUOTE$userId$QUOTE",
            "-r",
            "-t",
            "-S",
            size.toString(),
            "--",
            "-",
            "<",
            "$QUOTE$src$QUOTE",
        )
    }

    suspend fun install(userId: Int, src: String): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        installFromStream(userId = userId, src = src)
    } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
        // pm install -i com.android.vending --user "$userId" -r -t "$src"
        execute(
            "install",
            "-i",
            "com.android.vending",
            "--user",
            "$QUOTE$userId$QUOTE",
            "-r",
            "-t",
            "$QUOTE$src$QUOTE",
        )
    } else {
        // pm install --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -r -t "$src"
        execute(
            "install",
            "--bypass-low-target-sdk-block",
            "-i",
            "com.android.vending",
            "--user",
            "$QUOTE$userId$QUOTE",
            "-r",
            "-t",
            "$QUOTE$src$QUOTE",
        )
    }

    object Install {
        suspend fun create(userId: Int): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // pm install-create --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            // pm install-create -i com.android.vending --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else {
            // pm install-create --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--bypass-low-target-sdk-block",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        }

        suspend fun write(session: String, srcName: String, src: String): ShellResult = run {
            // pm install-write "$session" "$srcDir" "$src"
            execute(
                "install-write",
                "$QUOTE$session$QUOTE",
                "$QUOTE$srcName$QUOTE",
                "$QUOTE$src$QUOTE",
            )
        }

        suspend fun commit(session: String): ShellResult = run {
            // pm install-commit "$session"
            execute(
                "install-commit",
                "$QUOTE$session$QUOTE",
            )
        }
    }
}
