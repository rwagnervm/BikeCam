package com.bikecam.hud

import com.bikecam.settings.MountOrientation
import java.util.Locale

/**
 * Mostradores do HUD. Os [renderOnly] dependem de dados que só um arquivo de ciclocomputador traz
 * (potência, temperatura) ou da rota inteira (perfil): existem só na renderização de vídeo externo.
 */
enum class HudWidget(val label: String, val renderOnly: Boolean = false) {
    SPEED("Velocidade"),
    AVG_SPEED("Vel. média"),
    HEART_RATE("FC"),
    CADENCE("Cadência"),
    MAP("Mapa"),
    DISTANCE("Distância"),
    CLOCK("Relógio"),
    ALTITUDE("Altitude"),
    GRADE("Inclinação"),
    ELEVATION_GAIN("Ganho elev."),
    POWER("Potência", renderOnly = true),
    TEMPERATURE("Temperatura", renderOnly = true),
    ELEVATION_PROFILE("Perfil de elevação", renderOnly = true),
}

/** Posição de um mostrador: centro em coordenadas normalizadas (0..1) e escala relativa. */
data class WidgetPlacement(
    val widget: HudWidget,
    val enabled: Boolean,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
)

data class HudLayout(val placements: List<WidgetPlacement>) {

    fun update(widget: HudWidget, change: (WidgetPlacement) -> WidgetPlacement): HudLayout =
        HudLayout(placements.map { if (it.widget == widget) change(it) else it })

    operator fun get(widget: HudWidget): WidgetPlacement? = placements.firstOrNull { it.widget == widget }

    /** Desliga os mostradores fora de [available] (ex.: FC numa trilha sem FC). */
    fun onlyAvailable(available: Set<HudWidget>): HudLayout =
        HudLayout(placements.map { if (it.widget in available) it else it.copy(enabled = false) })

    /** Formato compacto para o DataStore: `WIDGET,ativo,x,y,escala;...`. */
    fun encode(): String = placements.joinToString(";") {
        "${it.widget.name},${if (it.enabled) 1 else 0},${it.x},${it.y},${it.scale}"
    }

    /** JSON para exportar/importar (versão 1). */
    fun toJson(orientation: MountOrientation): String = buildString {
        append("{\n  \"bikecam_layout\": 1,\n  \"orientation\": \"").append(orientation.name).append("\",\n  \"widgets\": [\n")
        placements.forEachIndexed { i, p ->
            append("    {\"widget\": \"").append(p.widget.name)
                .append("\", \"enabled\": ").append(p.enabled)
                .append(", \"x\": ").append(fmt(p.x))
                .append(", \"y\": ").append(fmt(p.y))
                .append(", \"scale\": ").append(fmt(p.scale)).append('}')
            append(if (i < placements.size - 1) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f

        private fun fmt(v: Float) = String.format(Locale.US, "%.4f", v)

        /**
         * Lê um layout salvo. Mostradores que não existem mais (ex.: a antiga bússola) são
         * descartados; os novos entram com a posição de [default].
         */
        fun decode(encoded: String?, default: HudLayout): HudLayout {
            if (encoded.isNullOrBlank()) return default
            val parsed = encoded.split(';').mapNotNull { parse(it) }.distinctBy { it.widget }
            return merge(parsed, default)
        }

        fun decode(encoded: String?, orientation: MountOrientation): HudLayout = decode(encoded, default(orientation))

        private fun merge(parsed: List<WidgetPlacement>, default: HudLayout): HudLayout {
            if (parsed.isEmpty()) return default
            val missing = default.placements.filter { d -> parsed.none { it.widget == d.widget } }
            return HudLayout(parsed + missing)
        }

        private fun parse(item: String): WidgetPlacement? {
            val f = item.split(',')
            if (f.size != 5) return null
            val widget = runCatching { HudWidget.valueOf(f[0]) }.getOrNull() ?: return null
            return placement(widget, f[1] == "1", f[2].toFloatOrNull(), f[3].toFloatOrNull(), f[4].toFloatOrNull())
        }

        private fun placement(widget: HudWidget, enabled: Boolean, x: Float?, y: Float?, scale: Float?): WidgetPlacement? {
            if (x == null || y == null || scale == null || !x.isFinite() || !y.isFinite() || !scale.isFinite()) return null
            return WidgetPlacement(widget, enabled, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), scale.coerceIn(MIN_SCALE, MAX_SCALE))
        }

        // `}` escapado: o regex do Android (ICU) recusa `}` solto, o da JVM aceita.
        private val JSON_WIDGET = Regex("""\{[^\{\}]*"widget"\s*:\s*"([A-Z_]+)"[^\{\}]*\}""")
        private fun jsonField(obj: String, key: String): String? =
            Regex(""""$key"\s*:\s*("?)([^",\}\s]+)\1""").find(obj)?.groupValues?.get(2)

        /**
         * Lê o JSON de [toJson]. Mostradores desconhecidos são ignorados e os ausentes vêm de
         * [default]. Retorna null se o texto não for um layout do BikeCam.
         */
        fun fromJson(json: String, default: HudLayout): HudLayout? {
            if (!json.contains("\"bikecam_layout\"")) return null
            val parsed = JSON_WIDGET.findAll(json).mapNotNull { m ->
                val widget = runCatching { HudWidget.valueOf(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
                val obj = m.value
                placement(
                    widget,
                    jsonField(obj, "enabled") == "true",
                    jsonField(obj, "x")?.toFloatOrNull(),
                    jsonField(obj, "y")?.toFloatOrNull(),
                    jsonField(obj, "scale")?.toFloatOrNull(),
                )
            }.toList().distinctBy { it.widget }
            if (parsed.isEmpty()) return null
            return merge(parsed, default)
        }

        /** Layout padrão da gravação ao vivo (os mostradores só de renderização ficam desligados). */
        fun default(orientation: MountOrientation): HudLayout {
            val base = when (orientation) {
                MountOrientation.LANDSCAPE -> listOf(
                    WidgetPlacement(HudWidget.CLOCK, true, 0.09f, 0.08f, 0.8f),
                    WidgetPlacement(HudWidget.GRADE, true, 0.40f, 0.08f, 0.8f),
                    WidgetPlacement(HudWidget.ELEVATION_GAIN, true, 0.60f, 0.08f, 0.8f),
                    WidgetPlacement(HudWidget.ALTITUDE, true, 0.91f, 0.08f, 0.8f),
                    WidgetPlacement(HudWidget.MAP, true, 0.89f, 0.32f, 1f),
                    WidgetPlacement(HudWidget.SPEED, true, 0.13f, 0.84f, 1.5f),
                    WidgetPlacement(HudWidget.AVG_SPEED, true, 0.33f, 0.89f, 0.85f),
                    WidgetPlacement(HudWidget.HEART_RATE, true, 0.89f, 0.62f, 1f),
                    WidgetPlacement(HudWidget.CADENCE, true, 0.89f, 0.78f, 1f),
                    WidgetPlacement(HudWidget.DISTANCE, true, 0.89f, 0.92f, 0.85f),
                )
                MountOrientation.PORTRAIT -> listOf(
                    WidgetPlacement(HudWidget.CLOCK, true, 0.17f, 0.05f, 0.85f),
                    WidgetPlacement(HudWidget.ALTITUDE, true, 0.84f, 0.05f, 0.85f),
                    WidgetPlacement(HudWidget.MAP, true, 0.80f, 0.18f, 1f),
                    WidgetPlacement(HudWidget.GRADE, true, 0.20f, 0.66f, 0.9f),
                    WidgetPlacement(HudWidget.ELEVATION_GAIN, true, 0.80f, 0.66f, 0.9f),
                    WidgetPlacement(HudWidget.AVG_SPEED, true, 0.20f, 0.76f, 0.9f),
                    WidgetPlacement(HudWidget.DISTANCE, true, 0.80f, 0.76f, 0.9f),
                    WidgetPlacement(HudWidget.HEART_RATE, true, 0.17f, 0.89f, 1f),
                    WidgetPlacement(HudWidget.SPEED, true, 0.50f, 0.89f, 1.6f),
                    WidgetPlacement(HudWidget.CADENCE, true, 0.83f, 0.89f, 1f),
                )
            }
            return HudLayout(base + renderOnlyPlacements(orientation).map { it.copy(enabled = false) })
        }

        /** Layout padrão da renderização: o da gravação ao vivo mais os mostradores novos ligados. */
        fun defaultRender(orientation: MountOrientation): HudLayout {
            val extra = renderOnlyPlacements(orientation).associateBy { it.widget }
            return HudLayout(default(orientation).placements.map { extra[it.widget] ?: it })
        }

        private fun renderOnlyPlacements(orientation: MountOrientation): List<WidgetPlacement> = when (orientation) {
            MountOrientation.LANDSCAPE -> listOf(
                WidgetPlacement(HudWidget.POWER, true, 0.11f, 0.62f, 1f),
                WidgetPlacement(HudWidget.TEMPERATURE, true, 0.22f, 0.08f, 0.8f),
                WidgetPlacement(HudWidget.ELEVATION_PROFILE, true, 0.58f, 0.90f, 1f),
            )
            MountOrientation.PORTRAIT -> listOf(
                WidgetPlacement(HudWidget.POWER, true, 0.50f, 0.66f, 0.9f),
                WidgetPlacement(HudWidget.TEMPERATURE, true, 0.50f, 0.05f, 0.85f),
                WidgetPlacement(HudWidget.ELEVATION_PROFILE, true, 0.50f, 0.56f, 1f),
            )
        }
    }
}
