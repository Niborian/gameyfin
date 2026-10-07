package org.gameyfin.app.core.download.files

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.download.bandwidth.SessionBandwidthManager
import org.gameyfin.app.core.download.bandwidth.SessionMonitoredOutputStream
import org.gameyfin.app.core.download.bandwidth.SessionThrottledOutputStream
import org.gameyfin.app.core.download.provider.DownloadProviderDto
import org.gameyfin.app.core.metrics.DownloadMetrics
import org.gameyfin.app.core.plugins.management.GameyfinPluginDescriptor
import org.gameyfin.app.core.plugins.management.GameyfinPluginManager
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantRetirementState
import org.gameyfin.app.games.entities.effectivePaths
import org.gameyfin.pluginapi.download.Download
import org.gameyfin.pluginapi.download.FileDownload
import org.gameyfin.pluginapi.download.DownloadProvider
import org.gameyfin.pluginapi.download.DownloadSelection
import org.gameyfin.pluginapi.download.DownloadSelectionContent
import org.gameyfin.pluginapi.download.SelectionAwareDownloadProvider
import org.springframework.stereotype.Service
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.time.DurationUnit
import kotlin.time.measureTime

@Service
class DownloadService(
    private val pluginManager: GameyfinPluginManager,
    private val configService: ConfigService,
    private val sessionBandwidthManager: SessionBandwidthManager,
    private val downloadMetrics: DownloadMetrics,
    private val downloadPathLeases: DownloadPathLeases,
) {

    companion object {
        private val log = KotlinLogging.logger { }
    }

    private val downloadPlugins: List<DownloadProvider>
        get() = pluginManager.getExtensions(DownloadProvider::class.java)

    fun getProviders(): List<DownloadProviderDto> {
        return downloadPlugins.map {
            val plugin = pluginManager.whichPlugin(it.javaClass.enclosingClass)
            val managementEntry = pluginManager.getManagementEntry(plugin.pluginId)
            val descriptor = plugin.descriptor as GameyfinPluginDescriptor

            DownloadProviderDto(
                key = it.javaClass.name,
                name = descriptor.pluginName,
                priority = managementEntry.priority,
                description = descriptor.pluginDescription,
                shortDescription = descriptor.pluginShortDescription,
            )
        }
    }

    fun getDownload(path: String, provider: String): Download {
        val provider = downloadPlugins.firstOrNull { it.javaClass.name == provider }
            ?: throw IllegalArgumentException("Download provider $provider not found")

        val lease = downloadPathLeases.acquire(listOf(Path.of(path)))
        try {
            val download = provider.download(Path.of(path))
            return if (download is FileDownload) FileDownload(
                data = downloadPathLeases.guard(download.data, lease), fileExtension = download.fileExtension, size = download.size
            ) else { lease.close(); download }
        } catch (error: Exception) { lease.close(); throw error }
    }

    fun getDownload(game: Game, provider: String, variantId: Long?, contentIds: List<Long>?): Download {
        val variant = selectVariant(game, variantId)
        val selectedContents = selectContents(variant, contentIds)

        val selectedProvider = downloadPlugins.firstOrNull { it.javaClass.name == provider }
        if (selectedProvider is SelectionAwareDownloadProvider) {
            val contentRoot = requireNotNull(Path.of(variant.path).toRealPath().parent) { "Variant has no content root" }
            val selection = DownloadSelection(selectedContents.map { content ->
                DownloadSelectionContent(content.name, content.effectivePaths().map {
                    Path.of(it).toRealPath().also { resolved ->
                        require(resolved.startsWith(contentRoot)) { "Selected path is outside variant content root" }
                    }
                })
            })
            // Retain both catalog aliases and canonical paths against retirement races.
            val sourcePaths = selectedContents.flatMap { it.effectivePaths() }.map { Path.of(it) }
            val lease = downloadPathLeases.acquire(sourcePaths + selection.contents.flatMap { it.paths })
            try {
                val download = selectedProvider.download(selection)
                return if (download is FileDownload) FileDownload(
                    data = downloadPathLeases.guard(download.data, lease),
                    fileExtension = download.fileExtension, size = download.size
                ) else { lease.close(); download }
            } catch (error: Exception) { lease.close(); throw error }
        }

        if (selectedContents.size == 1 &&
            selectedContents.single().effectivePaths().size == 1 &&
            selectedContents.single().path == variant.path
        ) {
            return getDownload(variant.path, provider)
        }

        val paths = selectedContents.flatMap { it.effectivePaths() }.map { Path.of(it) }
        val lease = downloadPathLeases.acquire(paths)
        val producerLease = downloadPathLeases.acquire(paths)
        return try {
            FileDownload(data = downloadPathLeases.guard(streamSelectedContentsAsZip(selectedContents) { producerLease.close() }, lease), fileExtension = "zip", size = null)
        } catch (error: Exception) { lease.close(); producerLease.close(); throw error }
    }

    fun estimateDownloadSize(game: Game, variantId: Long?, contentIds: List<Long>?): Long {
        val variant = selectVariant(game, variantId)
        return selectContents(variant, contentIds).sumOf { content ->
            content.fileSize ?: content.effectivePaths().sumOf { calculatePathSize(Path.of(it)) }
        }
    }

    private fun selectVariant(game: Game, variantId: Long?): GameVariant {
        val availableVariants = game.variants.filter {
            it.retirementState == VariantRetirementState.ACTIVE
        }
        if (availableVariants.isEmpty()) {
            throw IllegalStateException("Game '${game.id}' has no downloadable variants")
        }

        return if (variantId != null) {
            availableVariants.firstOrNull { it.id == variantId }
                ?: throw IllegalArgumentException("Variant $variantId not found for game ${game.id}")
        } else {
            availableVariants.firstOrNull { it.defaultLocked }
                ?: availableVariants.firstOrNull { it.name.equals("Normal", ignoreCase = true) && it.isLatestForVariant }
                ?: availableVariants.firstOrNull { it.isDefault }
                ?: availableVariants.firstOrNull { it.isLatestForVariant }
                ?: availableVariants.first()
        }
    }

    private fun selectContents(variant: GameVariant, contentIds: List<Long>?): List<VariantContent> {
        val selected = if (contentIds == null) {
            variant.contents.filter { it.required || it.defaultSelected }
        } else {
            val selectedIds = contentIds.toSet()
            val availableIds = variant.contents.mapNotNull { it.id }.toSet()
            require(availableIds.containsAll(selectedIds)) {
                "Selected content does not belong to variant ${variant.id}"
            }
            variant.contents.filter { it.id in selectedIds || it.required }
        }

        val contents = selected.ifEmpty {
            listOf(
                VariantContent(
                    variant = variant,
                    name = "Base game",
                    path = variant.path,
                    fileSize = variant.fileSize,
                    required = true,
                    defaultSelected = true
                )
            )
        }

        validateContentPaths(variant, contents)
        return contents
    }

    private fun validateContentPaths(variant: GameVariant, contents: List<VariantContent>) {
        val variantPath = Path.of(variant.path).toRealPath()
        // Variants can be folders below a game root, or files linked into a managed mirror.
        // Their parent is the smallest stable root that permits shared, sibling content without
        // allowing a selection to reach outside the game/mirror tree.
        val contentRoot = variantPath.parent
            ?: throw IllegalArgumentException("Variant ${variant.id} has no content root")

        contents.forEach { content ->
            val contentPaths = content.effectivePaths()
            require(contentPaths.isNotEmpty()) { "Content '${content.name}' has no download paths" }

            contentPaths.forEach { contentPath ->
                val resolvedPath = Path.of(contentPath).toRealPath()
                require(resolvedPath.startsWith(contentRoot)) {
                    "Content path '$contentPath' is outside variant ${variant.id} content root '$contentRoot'"
                }
            }
        }
    }

    private fun streamSelectedContentsAsZip(contents: List<VariantContent>, onComplete: () -> Unit): InputStream {
        val pipeIn = PipedInputStream(512 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        Thread.ofVirtual().start {
            try {
                ZipOutputStream(pipeOut).use { zip ->
                    contents.forEach { content ->
                        val paths = content.effectivePaths().map { Path.of(it) }
                        val entryRoot = safeZipEntryName(content.name.ifBlank { paths.first().name })

                        if (paths.size == 1) {
                            val path = paths.single()
                            if (path.isDirectory()) {
                                zipDirectory(zip, path, entryRoot)
                            } else {
                                zipFile(zip, path, entryRoot, includeFileName = content.name == path.name)
                            }
                        } else {
                            paths.forEach { path ->
                                val childEntryRoot = "$entryRoot/${safeZipEntryName(path.name)}"
                                if (path.isDirectory()) {
                                    zipDirectory(zip, path, childEntryRoot)
                                } else {
                                    zipFile(zip, path, childEntryRoot, includeFileName = true)
                                }
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            } finally {
                try {
                    pipeOut.close()
                } catch (_: IOException) {
                }
                onComplete()
            }
        }

        return pipeIn
    }

    private fun zipDirectory(zip: ZipOutputStream, root: Path, entryRoot: String) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val entry = ZipEntry("$entryRoot/${root.relativize(file).toString().replace('\\', '/')}")
                zip.putNextEntry(entry)
                Files.newInputStream(file, StandardOpenOption.READ).use { input ->
                    input.copyTo(zip, 512 * 1024)
                }
                zip.closeEntry()
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun zipFile(zip: ZipOutputStream, file: Path, entryRoot: String, includeFileName: Boolean) {
        val entryName = if (includeFileName) entryRoot else "$entryRoot.${file.extension}"
        zip.putNextEntry(ZipEntry(entryName))
        Files.newInputStream(file, StandardOpenOption.READ).use { input ->
            input.copyTo(zip, 512 * 1024)
        }
        zip.closeEntry()
    }

    private fun calculatePathSize(path: Path): Long {
        return try {
            if (path.isDirectory()) {
                Files.walk(path).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
                }
            } else {
                path.fileSize()
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun safeZipEntryName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "content" }
    }

    fun processDownload(
        data: InputStream,
        outputStream: OutputStream,
        game: Game,
        username: String?,
        sessionId: String,
        remoteIp: String
    ) {
        log.debug { "User '${username ?: "unknown user"}' (session: $sessionId) started download for game '${game.title}' [ID ${game.id}]" }

        val bandwidthLimitEnabled = configService.get(ConfigProperties.Downloads.BandwidthLimitEnabled) ?: false
        val bandwidthLimitMbps = configService.get(ConfigProperties.Downloads.BandwidthLimitMbps) ?: 0

        // Convert Mbps to bytes per second (1 Mbps = 125,000 bytes/second)
        val maxBytesPerSecond = if (bandwidthLimitEnabled && bandwidthLimitMbps > 0) {
            (bandwidthLimitMbps * 125_000).toLong()
        } else {
            0L // 0 means unlimited
        }

        // Always get a tracker to enable stats monitoring, even without throttling
        val tracker = sessionBandwidthManager.getTracker(sessionId, maxBytesPerSecond)

        val throttled = maxBytesPerSecond > 0

        val finalOutputStream = if (throttled) {
            log.debug {
                "Applying session-based bandwidth limit of $bandwidthLimitMbps Mbps ($maxBytesPerSecond bytes/sec) " +
                        "for download of '${game.title}' (active downloads for this session: ${tracker.activeDownloads.get()})"
            }
            SessionThrottledOutputStream(outputStream, tracker, game.id, username, remoteIp)
        } else {
            log.debug {
                "Monitoring download of '${game.title}' without bandwidth limit " +
                        "(active downloads for this session: ${tracker.activeDownloads.get()})"
            }
            SessionMonitoredOutputStream(outputStream, tracker, game.id, username, remoteIp)
        }

        downloadMetrics.recordDownloadStarted(throttled)

        try {
            finalOutputStream.use {
                val timeTaken = measureTime {
                    data.copyTo(finalOutputStream)
                    finalOutputStream.flush()
                }

                val bytesWritten = tracker.totalBytesTransferred
                downloadMetrics.recordDownloadCompleted(bytesWritten)

                log.debug {
                    "Download of game '${game.title}' [ID ${game.id}] by user '${username ?: "anonymous user"}' " +
                            "(session: $sessionId) completed in ${timeTaken.toString(DurationUnit.SECONDS)}"
                }
            }
        } catch (e: IOException) {
            downloadMetrics.recordDownloadFailed()

            // Client disconnected (cancelled download, network error, etc.)
            // This is expected behavior, log at debug level instead of error
            log.debug {
                "Download of game '${game.title}' [ID ${game.id}] by user '${username ?: "anonymous user"}' " +
                        "(session: $sessionId) was interrupted: ${e.message}"
            }
            // Don't re-throw - this is expected when clients cancel downloads
        } finally {
            try { data.close() } catch (_: IOException) { }
        }
    }
}
