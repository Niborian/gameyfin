package org.gameyfin.app.games.variants

object VariantVersionComparator : Comparator<String> {
    override fun compare(first: String, second: String): Int {
        val left = tokenize(first)
        val right = tokenize(second)
        val maxSize = maxOf(left.size, right.size)

        for (index in 0 until maxSize) {
            val leftPart = left.getOrNull(index) ?: VersionPart.Number(0)
            val rightPart = right.getOrNull(index) ?: VersionPart.Number(0)
            val result = comparePart(leftPart, rightPart)
            if (result != 0) return result
        }

        return 0
    }

    fun newest(versions: Iterable<String>): String? = versions.maxWithOrNull(this)

    private fun comparePart(first: VersionPart, second: VersionPart): Int {
        return when {
            first is VersionPart.Number && second is VersionPart.Number -> first.value.compareTo(second.value)
            first is VersionPart.Number && second is VersionPart.Text -> 1
            first is VersionPart.Text && second is VersionPart.Number -> -1
            first is VersionPart.Text && second is VersionPart.Text -> first.value.compareTo(second.value, ignoreCase = true)
            else -> 0
        }
    }

    private fun tokenize(version: String): List<VersionPart> {
        return version
            .split('.', '-', '_', '+')
            .filter { it.isNotBlank() }
            .map { part ->
                part.toLongOrNull()?.let { VersionPart.Number(it) }
                    ?: VersionPart.Text(part)
            }
    }

    private sealed interface VersionPart {
        data class Number(val value: Long) : VersionPart
        data class Text(val value: String) : VersionPart
    }
}
