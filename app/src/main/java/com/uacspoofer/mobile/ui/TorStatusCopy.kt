package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.tor.TorPhase

internal object TorStatusCopy {
    private val batchPattern = Regex(
        """Pol batch (\d+)/(\d+)\s*·\s*(\d+) bridge""",
    )

    fun bootstrapHint(percent: Int, persian: Boolean): String =
        if (persian) {
            "راه‌اندازی سایه $percent٪"
        } else {
            "Sayeh bootstrap $percent%"
        }

    fun connectingHint(
        persian: Boolean,
        percent: Int,
        phase: TorPhase,
        detail: String,
        showRouteProgress: Boolean,
    ): String = when {
        percent in 1..99 -> bootstrapHint(percent, persian)
        persian -> persianDetail(detail, phase, showRouteProgress)
        else -> englishDetail(detail, phase, showRouteProgress)
    }

    fun errorHint(persian: Boolean, detail: String): String? {
        if (detail.isBlank()) return null
        return if (persian) persianDetail(detail, TorPhase.FAILED, showRouteProgress = false) else detail
    }

    private fun englishDetail(detail: String, phase: TorPhase, showRouteProgress: Boolean): String {
        val trimmed = detail.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return when {
            phase == TorPhase.BRIDGING -> "Applying Pol bridge"
            showRouteProgress -> "Starting Sayeh / Pol"
            else -> "Starting Sayeh / Pol"
        }
    }

    private fun persianDetail(detail: String, phase: TorPhase, showRouteProgress: Boolean): String {
        val trimmed = detail.trim()
        batchPattern.find(trimmed)?.let { match ->
            val current = match.groupValues[1]
            val total = match.groupValues[2]
            val count = match.groupValues[3]
            return "دسته ${homeLtr("$current/$total")} · ${homeLtr(count)} بریج پل"
        }
        return when {
            trimmed.startsWith("Checking Pol") ->
                "در حال بررسی بریج‌های پل"
            trimmed.startsWith("Applying Pol") ->
                "در حال اعمال بریج پل"
            trimmed.startsWith("Starting Sayeh") ->
                "در حال شروع سایه"
            trimmed.startsWith("Routing device") ->
                "در حال عبور ترافیک دستگاه از سایه"
            trimmed.startsWith("Bootstrapping Sayeh") ->
                bootstrapHint(percentFromDetail(trimmed), persian = true)
            trimmed.startsWith("Sayeh circuit is ready") || trimmed.startsWith("Sayeh / Pol ready") ->
                "سایه آماده است"
            trimmed.startsWith("Reconnecting for ExitNodes") ->
                "اتصال مجدد برای کشور خروجی"
            trimmed.startsWith("Sayeh connect failed") || trimmed.startsWith("Sayeh reconnect failed") ->
                "اتصال سایه برقرار نشد"
            trimmed.isNotEmpty() -> homeLtr(trimmed)
            phase == TorPhase.BRIDGING -> "در حال اعمال بریج پل"
            showRouteProgress -> "در حال شروع سایه"
            else -> "در حال شروع سایه"
        }
    }

    private fun percentFromDetail(detail: String): Int =
        Regex("""(\d+)%""").find(detail)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
}
