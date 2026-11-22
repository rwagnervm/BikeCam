package com.bikecam.hud

import com.bikecam.settings.MountOrientation

enum class HudWidget(val label: String) {
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

    /** Formato compacto para o DataStore: `WIDGET,ativo,x,y,escala;...`. */
    fun encode(): String = placements.joinToString(";") {
        "${it.widget.name},${if (it.enabled) 1 else 0},${it.x},${it.y},${it.scale}"
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f

        /**
         * Lê um layout salvo. Mostradores que não existem mais (ex.: a antiga bússola) são
         * descartados; os novos entram com a posição padrão.
         */
        fun decode(encoded: String?, orientation: MountOrientation): HudLayout {
            val default = default(orientation)
            if (encoded.isNullOrBlank()) return default
            val parsed = encoded.split(';').mapNotNull { parse(it) }.distinctBy { it.widget }
            if (parsed.isEmpty()) return default
            val missing = default.placements.filter { d -> parsed.none { it.widget == d.widget } }
            return HudLayout(parsed + missing)
        }

        private fun parse(item: String): WidgetPlacement? {
            val f = item.split(',')
            if (f.size != 5) return null
            val widget = runCatching { HudWidget.valueOf(f[0]) }.getOrNull() ?: return null
            val x = f[2].toFloatOrNull() ?: return null
            val y = f[3].toFloatOrNull() ?: return null
            val scale = f[4].toFloatOrNull() ?: return null
            return WidgetPlacement(widget, f[1] == "1", x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), scale.coerceIn(MIN_SCALE, MAX_SCALE))
        }

        fun default(orientation: MountOrientation): HudLayout = when (orientation) {
            MountOrientation.LANDSCAPE -> HudLayout(
                listOf(
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
                ),
            )
            MountOrientation.PORTRAIT -> HudLayout(
                listOf(
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
                ),
            )
        }
    }
}
