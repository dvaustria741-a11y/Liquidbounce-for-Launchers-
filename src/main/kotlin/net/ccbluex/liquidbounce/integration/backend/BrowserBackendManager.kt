/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.integration.backend

import com.mojang.blaze3d.systems.RenderSystem
import net.ccbluex.liquidbounce.LiquidBounce.CLIENT_NAME
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.BrowserReadyEvent
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.backend.backends.cef.CefBrowserBackend
import net.ccbluex.liquidbounce.integration.backend.backends.external.ExternalSystemBrowserBackend
import net.ccbluex.liquidbounce.integration.backend.browser.GlobalBrowserSettings
import net.ccbluex.liquidbounce.integration.interop.persistant.PersistentLocalStorage
import net.ccbluex.liquidbounce.integration.task.TaskManager
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

object BrowserBackendManager : EventListener {

    private val logger: Logger = LogManager.getLogger("$CLIENT_NAME/BrowserBackendManager")

    val isInitialized: Boolean
        get() = backend?.isInitialized ?: false
    var backend: BrowserBackend? = null

    var isSkipping = env("LB_BROWSER_SKIP", "net.ccbluex.liquidbounce.browser.skip")?.toBoolean()
        ?: isRunningOnMobileLauncher()
    val backendName = env("LB_BROWSER_BACKEND", "net.ccbluex.liquidbounce.browser.backend") ?: "cef"
    val disableAcceleration = env("LB_BROWSER_DISABLE_ACCELERATION",
        "net.ccbluex.liquidbounce.browser.disableAcceleration")?.toBoolean() ?: false

    /**
     * Detects whether LiquidBounce is running inside an Android-based Minecraft launcher
     * (e.g. ZalithLauncher v2, PojavLauncher, MojoLauncher, etc.).
     * JCEF/MCEF is incompatible with Android ARM, so we skip it automatically.
     */
    private fun isRunningOnMobileLauncher(): Boolean {
        // Android exposes these system properties regardless of the JVM in use
        if (System.getProperty("android.os.build.version.release") != null) return true
        if (System.getProperty("android.os.build.model") != null) return true
        val vmVendor = System.getProperty("java.vm.vendor") ?: ""
        if (vmVendor.contains("android", ignoreCase = true)) return true
        val runtimeName = System.getProperty("java.runtime.name") ?: ""
        if (runtimeName.contains("android", ignoreCase = true)) return true

        // ZalithLauncher v2, PojavLauncher, MojoLauncher and forks set one of these
        val knownEnvVars = listOf(
            "POJAV_ENVIRON", "ZALITH_ENVIRON", "MOJO_ENVIRON",
            "POJAV_HOME", "ZALITH_HOME",
            "MOVTERY_LAUNCHER", "ZALITH_LAUNCHER",
            "ANDROID_LAUNCHER"
        )
        if (knownEnvVars.any { System.getenv(it) != null }) return true

        // java.home pointing into Android data directories is a dead giveaway
        val javaHome = System.getProperty("java.home") ?: ""
        if (javaHome.contains("/data/data/") || javaHome.contains("/data/user/")) return true

        // ARM + Linux + no display = almost certainly an Android launcher
        val arch = System.getProperty("os.arch") ?: ""
        val osName = System.getProperty("os.name") ?: ""
        if (osName.equals("linux", ignoreCase = true) &&
            (arch.startsWith("aarch64") || arch.startsWith("arm"))) {
            val hasDisplay = System.getenv("DISPLAY")
                ?: System.getenv("WAYLAND_DISPLAY")
                ?: System.getenv("DESKTOP_SESSION")
                ?: System.getenv("XDG_CURRENT_DESKTOP")
            if (hasDisplay == null) return true
        }
        return false
    }

    fun init() {
        PersistentLocalStorage
    }

    fun makeDependenciesAvailable(taskManager: TaskManager) {
        if (isSkipping) {
            logger.warn("Browser skipped — mobile launcher or LB_BROWSER_SKIP set.")
            return
        }

        val browserBackend = when (backendName) {
            "none" -> {
                logger.warn("Environment variable 'LB_BROWSER_BACKEND' is set to 'none'.")
                isSkipping = true
                return
            }
            "cef" -> CefBrowserBackend()
            "external" -> ExternalSystemBrowserBackend()
            else -> error("Unknown browser backend: $backendName")
        }
        this.backend = browserBackend
        runCatching {
            browserBackend.makeDependenciesAvailable(taskManager, ::start)
        }.onFailure { ex ->
            logger.warn("Browser backend unavailable, falling back to no-browser mode: ${ex.message}")
            isSkipping = true
        }
    }

    fun start() {
        logger.info("Initializing browser...")
        RenderSystem.assertOnRenderThread()

        val browserBackend = backend ?: return
        browserBackend.start()

        if (disableAcceleration) {
            logger.warn("Environment variable 'LB_BROWSER_DISABLE_ACCELERATION' is set to 'true'.")
        }
        GlobalBrowserSettings
        EventManager.callEvent(BrowserReadyEvent)
        logger.info("Successfully initialized browser.")
    }

    fun stop() = runCatching {
        backend?.stop()
    }.onFailure {
        logger.error("Failed to shutdown browser.", it)
    }.onSuccess {
        logger.info("Successfully shutdown browser.")
    }

    fun forceUpdate() = mc.execute {
        val browserBackend = backend ?: return@execute
        for (browser in browserBackend.browsers) {
            try {
                browser.viewport = browser.viewport
            } catch (e: Exception) {
                logger.error("Failed to update tab of '${browser.url}'", e)
            }
        }
    }

    @Suppress("unused")
    private val gameRenderHandler = handler<GameRenderEvent>(priority = FIRST_PRIORITY) {
        val browserBackend = backend ?: return@handler
        if (!browserBackend.isInitialized) return@handler
        browserBackend.update()
    }

}
