package dev.sushnt.journal.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/** Runs one statement that returns no rows (CREATE, INSERT, UPDATE, DELETE). */
internal fun SQLiteConnection.exec(sql: String, bind: (SQLiteStatement) -> Unit = {}) {
    val st = prepare(sql)
    try {
        bind(st)
        st.step()
    } finally {
        st.close()
    }
}

/** Runs a SELECT and turns each row into a value with [read]. */
internal fun <T> SQLiteConnection.queryRows(
    sql: String,
    bind: (SQLiteStatement) -> Unit = {},
    read: (SQLiteStatement) -> T,
): List<T> {
    val st = prepare(sql)
    try {
        bind(st)
        val out = mutableListOf<T>()
        while (st.step()) out += read(st)
        return out
    } finally {
        st.close()
    }
}
