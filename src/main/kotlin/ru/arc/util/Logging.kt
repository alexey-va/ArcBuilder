package ru.arc.util

import ru.arc.logging.ArcLogging

/** Compatibility facade while the extracted sources move to ArcLogging. */
object Logging {
    @JvmStatic fun info(message: String, vararg args: Any?) = ArcLogging.info(message, *args)
    @JvmStatic fun warn(message: String, vararg args: Any?) = ArcLogging.warn(message, *args)
    @JvmStatic fun error(message: String, vararg args: Any?) = ArcLogging.error(message, *args)
    @JvmStatic fun debug(message: String, vararg args: Any?) = ArcLogging.debug(message, *args)
}
