package dev.veneranative.core.archive

/** Stable, locale-independent ordering for page names. */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ad = a[i].isDigit(); val bd = b[j].isDigit()
            if (ad && bd) {
                val ai = i; val bj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val an = a.substring(ai, i).trimStart('0').ifEmpty { "0" }
                val bn = b.substring(bj, j).trimStart('0').ifEmpty { "0" }
                val byLength = an.length.compareTo(bn.length)
                if (byLength != 0) return byLength
                val byNumber = an.compareTo(bn)
                if (byNumber != 0) return byNumber
                val byZeroes = (i - ai).compareTo(j - bj)
                if (byZeroes != 0) return byZeroes
            } else {
                val ac = a[i].lowercaseChar(); val bc = b[j].lowercaseChar()
                if (ac != bc) return ac.compareTo(bc)
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b, ignoreCase = true)
    }
}
