package dev.sushnt.journal.data

/**
 * Three-way merge of journal text, line by line (the same idea as git merge).
 *
 * [base] is the text both devices started from, [mine] is this device's edit and
 * [theirs] is the other device's edit. Lines changed on only one side are taken from
 * that side. Where both sides changed the same lines differently, BOTH versions are
 * kept (ours first, then theirs), so no writing is ever lost.
 */
fun mergeText(base: String, mine: String, theirs: String): String {
    if (mine == theirs) return mine
    if (mine == base) return theirs
    if (theirs == base) return mine

    val b = base.split('\n')
    val a = mine.split('\n')
    val t = theirs.split('\n')
    // Very long texts: matching every line against every line would be slow. Keep both.
    if (b.size.toLong() * maxOf(a.size, t.size) > 4_000_000L) return "$mine\n\n$theirs"

    val inA = matchLines(b, a) // base index -> mine index (for lines both kept unchanged)
    val inT = matchLines(b, t)

    val out = mutableListOf<String>()
    var bi = 0
    var ai = 0
    var ti = 0
    fun takeChunk(bEnd: Int, aEnd: Int, tEnd: Int) {
        val bc = b.subList(bi, bEnd)
        val ac = a.subList(ai, aEnd)
        val tc = t.subList(ti, tEnd)
        when {
            ac == bc -> out += tc       // only theirs changed here
            tc == bc -> out += ac       // only mine changed here
            ac == tc -> out += ac       // both made the same change
            else -> {                   // both changed it differently: keep both
                out += ac
                out += tc
            }
        }
    }

    // Walk the base lines that are still there, unchanged, in BOTH versions ("anchors").
    for (k in b.indices) {
        val ak = inA[k] ?: continue
        val tk = inT[k] ?: continue
        if (ak < ai || tk < ti) continue
        takeChunk(k, ak, tk)
        out += b[k]
        bi = k + 1
        ai = ak + 1
        ti = tk + 1
    }
    takeChunk(b.size, a.size, t.size)
    return out.joinToString("\n")
}

/** Longest common subsequence of lines: for each base line kept in [other], where it is there. */
private fun matchLines(base: List<String>, other: List<String>): Array<Int?> {
    val n = base.size
    val m = other.size
    // lcs[i][j] = length of the LCS of base[i..] and other[j..]
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (base[i] == other[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val result = arrayOfNulls<Int>(n)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            base[i] == other[j] -> {
                result[i] = j
                i++
                j++
            }
            lcs[i + 1][j] >= lcs[i][j + 1] -> i++
            else -> j++
        }
    }
    return result
}
