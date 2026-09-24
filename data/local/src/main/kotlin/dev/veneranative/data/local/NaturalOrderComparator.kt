package dev.veneranative.data.local

/** Case-insensitive natural order with numeric runs compared by value and leading zeros stable. */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            val ca = left[a]
            val cb = right[b]
            if (ca.isDigit() && cb.isDigit()) {
                val aStart = a
                val bStart = b
                while (a < left.length && left[a].isDigit()) a++
                while (b < right.length && right[b].isDigit()) b++
                val aDigits = left.substring(aStart, a).trimStart('0').ifEmpty { "0" }
                val bDigits = right.substring(bStart, b).trimStart('0').ifEmpty { "0" }
                val numeric = aDigits.length.compareTo(bDigits.length).takeIf { it != 0 }
                    ?: aDigits.compareTo(bDigits).takeIf { it != 0 }
                if (numeric != null) return numeric
                val zeros = left.substring(aStart, a).length.compareTo(right.substring(bStart, b).length)
                if (zeros != 0) return zeros
                continue
            }
            val folded = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (folded != 0) return folded
            a++
            b++
        }
        return (left.length - a).compareTo(right.length - b).takeIf { it != 0 }
            ?: left.compareTo(right)
    }
}
