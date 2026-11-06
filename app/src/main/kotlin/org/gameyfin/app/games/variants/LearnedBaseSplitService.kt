package org.gameyfin.app.games.variants

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import org.gameyfin.app.games.GameService
import org.gameyfin.app.games.dto.AttachVariantContentEntryDto
import org.gameyfin.app.games.dto.GameAdminEvent
import org.gameyfin.app.games.dto.GameUserEvent
import org.gameyfin.app.games.dto.LearnedBaseSplitItemDto
import org.gameyfin.app.games.dto.LearnedBaseSplitPreviewDto
import org.gameyfin.app.games.dto.LearnedBaseSplitReviewDto
import org.gameyfin.app.games.dto.LearnedBaseSplitRuleDto
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
import org.gameyfin.app.games.entities.VariantContentType
import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.games.entities.VariantRetirementState
import org.gameyfin.app.games.entities.effectivePaths
import org.gameyfin.app.games.extensions.toAdminDto
import org.gameyfin.app.games.extensions.toUserDto
import org.gameyfin.app.games.repositories.GameRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.HexFormat
import java.util.Locale

@Service
class LearnedBaseSplitService(
    private val games: GameRepository,
    private val grouping: GameVariantGroupingService,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper
) {
    private data class Layout(val version: String, val archiveStyle: String, val paths: Map<String, Path>) {
        fun key(): List<String> = listOf(archiveStyle) + paths.keys.sorted()
    }

    internal data class Choice @JsonCreator constructor(
        @param:JsonProperty("slot") val slot: String,
        @param:JsonProperty("label") val label: String?,
        @param:JsonProperty("type") val type: VariantContentType,
        @param:JsonProperty("required") val required: Boolean,
        @param:JsonProperty("defaultSelected") val defaultSelected: Boolean,
        @param:JsonProperty("tags") val tags: List<String>
    )

    internal data class Template @JsonCreator constructor(
        @param:JsonProperty("layout") val layout: List<String>,
        @param:JsonProperty("choices") val choices: List<Choice>
    )

    private enum class Mode { REVIEW, AUTOMATIC, MUTED }

    private data class Rule(
        val id: String,
        val libraryId: Long,
        val libraryName: String,
        val template: Template,
        val streak: Int = 3,
        val nextOffer: Int = 6,
        val mode: Mode = Mode.REVIEW
    ) {
        val offer get() = mode == Mode.REVIEW && streak >= nextOffer

        fun dto() = LearnedBaseSplitRuleDto(
            id, libraryName, "Versioned archive + " + template.layout.drop(1).filter { it != BASE }.joinToString(", "),
            streak, mode == Mode.AUTOMATIC, mode == Mode.MUTED, offer
        )
    }

    private data class Match(val variant: GameVariant, val broad: VariantContent, val layout: Layout)
    private data class Proposal(val match: Match, val rule: Rule, val entries: List<AttachVariantContentEntryDto>, val revision: String)
    private data class SavedContent(
        val gameId: Long, val root: String, val paths: List<String>, val label: String,
        val type: VariantContentType, val required: Boolean, val defaultSelected: Boolean, val tags: List<String>
    )

    @Transactional(readOnly = true)
    fun review(gameId: Long): LearnedBaseSplitReviewDto {
        val game = games.findByIdOrNull(gameId) ?: return LearnedBaseSplitReviewDto(null, null)
        val offer = rulesForLibrary(requireNotNull(game.library.id)).firstOrNull { rule ->
            rule.offer && decided(rule.id, gameId, approvedOnly = true)
        }
        val preview = proposal(game)?.let { p ->
            LearnedBaseSplitPreviewDto(
                p.revision, p.match.layout.version,
                p.entries.map {
                    LearnedBaseSplitItemDto(
                        it.contentName, Path.of(it.path).fileName.toString(), it.contentType,
                        it.required, it.defaultSelected, it.tags.orEmpty().sorted()
                    )
                },
                p.rule.streak
            )
        }
        return LearnedBaseSplitReviewDto(preview, offer?.dto())
    }

    @Transactional
    fun decide(gameId: Long, revision: String, approve: Boolean): Game? {
        val libraryId = jdbc.query("SELECT LIBRARY_ID FROM GAME WHERE ID = ? FOR UPDATE", { rs, _ -> rs.getLong(1) }, gameId)
            .singleOrNull() ?: error("Game is no longer available")
        lockLibrary(libraryId)
        val game = games.findByIdOrNull(gameId) ?: error("Game is no longer available")
        val p = requireNotNull(proposal(game)) { "This suggestion changed. Reload it before deciding." }
        require(p.revision == revision) { "This suggestion changed. Reload it before deciding." }
        if (approve) {
            val sizes = requireNotNull(sizes(p.match.layout)) { "The source layout changed or could not be verified." }
            require(findMatch(game)?.layout == p.match.layout) { "The source layout changed. Reload before approving." }
            apply(game, p.match, p.entries, sizes)
        }
        val updated = if (approve) p.rule.copy(streak = p.rule.streak + 1)
        else p.rule.copy(streak = 0, nextOffer = 6)
        save(updated)
        jdbc.update("INSERT INTO LEARNED_BASE_SPLIT_DECISION (RULE_ID, GAME_ID, APPROVED) VALUES (?, ?, ?)", p.rule.id, gameId, approve)
        return if (approve) saveSplit(game) else null
    }

    @Transactional(readOnly = true)
    fun settings(): List<LearnedBaseSplitRuleDto> =
        jdbc.query("$RULE_SELECT ORDER BY L.NAME, R.ID", { rs, _ -> readRule(rs).dto() })

    @Transactional
    fun setPreference(id: String, choice: String): LearnedBaseSplitRuleDto {
        val before = rule(id) ?: error("Learned layout is no longer available")
        lockLibrary(before.libraryId)
        val current = rule(id) ?: error("Learned layout is no longer available")
        val updated = when (choice) {
            "enable" -> {
                require(current.offer) { "Automatic application has not been offered for this layout" }
                current.copy(mode = Mode.AUTOMATIC)
            }
            "later", "mute" -> {
                require(current.offer) { "There is no pending automation offer for this layout" }
                current.copy(mode = if (choice == "mute") Mode.MUTED else Mode.REVIEW, nextOffer = current.streak + 3)
            }
            "ask" -> {
                require(current.mode == Mode.MUTED) { "This layout is already set to ask" }
                current.copy(mode = Mode.REVIEW, nextOffer = maxOf(6, current.streak))
            }
            "disable" -> {
                require(current.mode == Mode.AUTOMATIC) { "This layout is already set to review" }
                current.copy(mode = Mode.REVIEW, nextOffer = current.streak + 3)
            }
            else -> throw IllegalArgumentException("Unknown learned layout preference")
        }
        save(updated)
        return updated.dto()
    }

    /** Invoked only for a newly imported game, in its existing scan transaction. */
    fun applyAutomatically(game: Game) {
        val libraryId = game.library.id ?: return
        if (!jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM LEARNED_BASE_SPLIT WHERE LIBRARY_ID = ? AND MODE = 'AUTOMATIC')",
                Boolean::class.java, libraryId
            ).let { it == true }) return
        val match = findMatch(game) ?: return
        val id = ruleId(libraryId, match.layout)
        if (rule(id)?.mode != Mode.AUTOMATIC) return
        val sizes = sizes(match.layout) ?: return
        lockLibrary(libraryId)
        val rule = rule(id)?.takeIf { it.mode == Mode.AUTOMATIC } ?: return
        if (findMatch(game)?.layout != match.layout || rule.template.layout != match.layout.key()) return
        apply(game, match, entries(match.layout, rule.template), sizes)
        saveSplit(game)
    }

    private fun proposal(game: Game): Proposal? {
        val match = findMatch(game) ?: return null
        val libraryId = game.library.id ?: return null
        val gameId = game.id ?: return null
        val id = ruleId(libraryId, match.layout)
        val existing = rule(id)
        if (existing?.mode == Mode.AUTOMATIC || (existing != null && decided(id, gameId))) return null
        val rule = existing ?: Rule(id, libraryId, "", learn(gameId, libraryId, match.layout) ?: return null)
        if (rule.template.layout != match.layout.key()) return null
        val entries = entries(match.layout, rule.template)
        val revision = hash(listOf(id, gameId, match.variant.id, match.variant.version, match.broad.id, entries))
        return Proposal(match, rule, entries, revision)
    }

    private fun learn(gameId: Long, libraryId: Long, layout: Layout): Template? {
        // Project only saved selections. Loading Library.games would materialize the whole catalogue graph.
        val history = jdbc.query(HISTORY_SELECT, { rs, _ ->
            SavedContent(
                rs.getLong("GAME_ID"), rs.getString("ROOT_PATH"),
                strings(rs, "PATHS").ifEmpty { listOf(rs.getString("PATH")) }, rs.getString("NAME"),
                VariantContentType.valueOf(rs.getString("TYPE")), rs.getBoolean("REQUIRED"),
                rs.getBoolean("DEFAULT_SELECTED"), strings(rs, "TAGS")
            )
        }, libraryId, gameId)
        if (history.size > 4096) return null
        var template: Template? = null
        val sources = mutableSetOf<String>()
        for (contents in history.groupBy { it.gameId }.values) {
            if (contents.size !in 2..MAX_CHILDREN) continue
            val root = filesystemPreview { Path.of(contents.first().root).toAbsolutePath().normalize() } ?: continue
            val byPath = filesystemPreview {
                contents.mapNotNull { c ->
                    c.paths.singleOrNull()?.let { Path.of(it).toAbsolutePath().normalize() to c }
                }.toMap()
            } ?: continue
            if (byPath.size != contents.size || byPath.keys.any { it.parent != root }) continue
            val base = byPath.entries.singleOrNull { it.value.type == VariantContentType.BASE } ?: continue
            if (!base.value.required || !base.value.defaultSelected) continue
            val historical = layout(root, base.key, byPath.keys.filter { it != base.key }) ?: continue
            if (historical.key() != layout.key()) continue
            val choices = historical.paths.map { (slot, path) ->
                val content = byPath.getValue(path)
                val label = content.label.takeUnless { it == path.fileName.toString() }
                Choice(slot, label, content.type, content.required, content.defaultSelected, content.tags)
            }.sortedBy { it.slot }
            // Conflicting historical classifications are ambiguous even if three other games agree.
            val example = Template(layout.key(), choices)
            if (template != null && template != example) return null
            template = example
            sources.add(root.toString())
        }
        return template.takeIf { sources.size >= 3 }
    }

    private fun findMatch(game: Game): Match? = filesystemPreview {
        val variant = game.variants.singleOrNull()?.takeIf { it.scanManaged && eligible(it) } ?: return@filesystemPreview null
        val broad = variant.contents.singleOrNull()?.takeIf {
            it.type == VariantContentType.BASE && it.required && it.defaultSelected && it.tags.isEmpty()
        } ?: return@filesystemPreview null
        val root = Path.of(game.metadata.path).toAbsolutePath().normalize()
        val libraryId = game.library.id ?: return@filesystemPreview null
        if (Path.of(variant.path).toAbsolutePath().normalize() != root ||
            broad.effectivePaths().singleOrNull()?.let { Path.of(it).toAbsolutePath().normalize() } != root
        ) return@filesystemPreview null
        val roots = jdbc.queryForList(
            """SELECT D.INTERNAL_PATH FROM LIBRARY L JOIN LIBRARY_DIRECTORIES M ON M.LIBRARY_ID = L.ID
                JOIN DIRECTORY_MAPPING D ON D.ID = M.DIRECTORIES_ID WHERE L.ID = ? AND L.STORAGE_MODE = 'DIRECT'""",
            String::class.java, libraryId
        )
        if (roots.none { root.startsWith(Path.of(it).toAbsolutePath().normalize()) }) return@filesystemPreview null
        if (root.toRealPath() != root || !Files.isDirectory(root, NOFOLLOW_LINKS)) return@filesystemPreview null
        val children = Files.newDirectoryStream(root).use { it.asSequence().take(MAX_CHILDREN + 1).toList() }
        if (children.size !in 2..MAX_CHILDREN) return@filesystemPreview null
        val archive = children.singleOrNull { Files.isRegularFile(it, NOFOLLOW_LINKS) } ?: return@filesystemPreview null
        val folders = children.filter { it != archive }
        if (folders.any { !Files.isDirectory(it, NOFOLLOW_LINKS) }) return@filesystemPreview null
        val layout = layout(root, archive, folders) ?: return@filesystemPreview null
        if (variant.version != "0" && variant.version != layout.version) return@filesystemPreview null
        Match(variant, broad, layout)
    }

    private fun eligible(variant: GameVariant): Boolean =
        variant.linkStatus == VariantLinkStatus.DIRECT && variant.retirementState == VariantRetirementState.ACTIVE &&
            variant.quarantinePath == null && variant.supersededAt == null

    private fun layout(root: Path, archive: Path, folders: List<Path>): Layout? {
        val match = ARCHIVE.matchEntire(archive.fileName.toString()) ?: return null
        if (normalizedTitle(root.fileName.toString()) != normalizedTitle(match.groupValues[1])) return null
        val extras = folders.associateBy { it.fileName.toString().lowercase(Locale.ROOT) }
        if (extras.isEmpty() || extras.size != folders.size ||
            extras.keys.any { it == BASE || it.startsWith('.') || it.length > 120 || it.any(Char::isISOControl) }
        ) return null
        return Layout(match.groupValues[2], match.groupValues[3].lowercase(Locale.ROOT), mapOf(BASE to archive) + extras)
    }

    private fun normalizedTitle(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun entries(layout: Layout, template: Template) = template.choices.map { c ->
        val path = layout.paths.getValue(c.slot)
        AttachVariantContentEntryDto(path.toString(), c.label ?: path.fileName.toString(), c.type, c.required, c.defaultSelected, c.tags.toSet())
    }

    private fun sizes(layout: Layout): Map<String, Long>? = filesystemPreview {
        val result = mutableMapOf<String, Long>()
        var remaining = 4096
        for (path in layout.paths.values) {
            var bytes = 0L
            Files.walk(path, 16).use { stream ->
                val files = stream.iterator()
                while (files.hasNext()) {
                    if (--remaining < 0) return@filesystemPreview null
                    val file = files.next()
                    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    when {
                        attributes.isRegularFile -> bytes = Math.addExact(bytes, attributes.size())
                        !attributes.isDirectory || path.relativize(file).nameCount >= 16 -> return@filesystemPreview null
                    }
                }
            }
            result[path.toString()] = bytes
        }
        result
    }

    private inline fun <T> filesystemPreview(block: () -> T?): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: UncheckedIOException) {
        null
    } catch (_: DirectoryIteratorException) {
        null
    } catch (_: InvalidPathException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun apply(game: Game, match: Match, entries: List<AttachVariantContentEntryDto>, sizes: Map<String, Long>) {
        grouping.replaceLearnedBase(game, match.variant, match.broad, match.layout.version, entries, sizes)
    }

    private fun saveSplit(game: Game): Game {
        val saved = games.saveAndFlush(game)
        // Updating only a variant does not fire Game's @PostUpdate callback.
        val user = GameUserEvent.Updated(saved.toUserDto())
        val admin = GameAdminEvent.Updated(saved.toAdminDto())
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                GameService.emitUser(user)
                GameService.emitAdmin(admin)
            }
        })
        return saved
    }

    private fun lockLibrary(id: Long) {
        check(jdbc.queryForObject("SELECT ID FROM LIBRARY WHERE ID = ? FOR UPDATE", Long::class.java, id) == id)
    }

    private fun hash(value: Any): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)))

    private fun ruleId(libraryId: Long, layout: Layout) = "$libraryId:${hash(layout.key())}"
    private fun rule(id: String) = jdbc.query("$RULE_SELECT WHERE R.ID = ?", { rs, _ -> readRule(rs) }, id).singleOrNull()
    private fun rulesForLibrary(id: Long) =
        jdbc.query("$RULE_SELECT WHERE R.LIBRARY_ID = ? ORDER BY R.ID", { rs, _ -> readRule(rs) }, id)

    private fun readRule(rs: ResultSet) = Rule(
        rs.getString("ID"), rs.getLong("LIBRARY_ID"), rs.getString("LIBRARY_NAME"),
        mapper.readValue(rs.getString("TEMPLATE"), Template::class.java),
        rs.getInt("STREAK"), rs.getInt("NEXT_OFFER"), Mode.valueOf(rs.getString("MODE"))
    )

    private fun strings(rs: ResultSet, column: String): List<String> {
        val value = rs.getArray(column)
        return try {
            (value.array as Array<*>).filterIsInstance<String>()
        } finally {
            value.free()
        }
    }

    private fun save(rule: Rule) {
        jdbc.update(
            "MERGE INTO LEARNED_BASE_SPLIT (ID, LIBRARY_ID, TEMPLATE, STREAK, NEXT_OFFER, MODE) KEY (ID) VALUES (?, ?, ?, ?, ?, ?)",
            rule.id, rule.libraryId, mapper.writeValueAsString(rule.template), rule.streak, rule.nextOffer, rule.mode.name
        )
    }

    private fun decided(ruleId: String, gameId: Long, approvedOnly: Boolean = false) =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM LEARNED_BASE_SPLIT_DECISION WHERE RULE_ID = ? AND GAME_ID = ? AND (NOT ? OR APPROVED))",
            Boolean::class.java, ruleId, gameId, approvedOnly
        ) == true

    companion object {
        private const val BASE = "@base"
        private const val MAX_CHILDREN = 16
        private val ARCHIVE = Regex("""(?i)^(.+?)[._ -]v(\d+(?:\.\d+){1,4})((?:[-_][a-z0-9][a-z0-9_-]{0,31})?\.(?:rar|zip|7z))$""")
        private const val RULE_SELECT =
            "SELECT R.ID, R.LIBRARY_ID, L.NAME AS LIBRARY_NAME, R.TEMPLATE, R.STREAK, R.NEXT_OFFER, R.MODE " +
                "FROM LEARNED_BASE_SPLIT R JOIN LIBRARY L ON L.ID = R.LIBRARY_ID"
        private const val HISTORY_SELECT = """
            SELECT G.ID AS GAME_ID, G.PATH AS ROOT_PATH, C.PATH, C.NAME, C.TYPE, C.REQUIRED, C.DEFAULT_SELECTED,
                ARRAY(SELECT P.PATH FROM VARIANT_CONTENT_PATHS P WHERE P.VARIANT_CONTENT_ID = C.ID ORDER BY P.PATH_INDEX) AS PATHS,
                ARRAY(SELECT T.TAGS FROM VARIANT_CONTENT_TAGS T WHERE T.VARIANT_CONTENT_ID = C.ID ORDER BY T.TAGS) AS TAGS
            FROM GAME G JOIN GAME_VARIANT V ON V.GAME_ID = G.ID JOIN VARIANT_CONTENT C ON C.VARIANT_ID = V.ID
            WHERE G.LIBRARY_ID = ? AND G.ID <> ? AND V.SCAN_MANAGED = FALSE AND V.PATH = G.PATH
                AND V.LINK_STATUS = 'DIRECT' AND V.RETIREMENT_STATE = 'ACTIVE' AND V.QUARANTINE_PATH IS NULL
                AND V.SUPERSEDED_AT IS NULL
                AND NOT EXISTS (SELECT 1 FROM GAME_VARIANT O WHERE O.GAME_ID = G.ID AND O.ID <> V.ID)
            ORDER BY G.ID, C.ID LIMIT 4097
        """
    }
}
