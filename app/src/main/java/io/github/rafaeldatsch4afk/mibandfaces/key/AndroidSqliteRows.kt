package io.github.rafaeldatsch4afk.mibandfaces.key

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Abre uma cópia temporária do banco (e do WAL, se houver) com o SQLite do Android e devolve as
 * linhas de qualquer tabela que tenham um valor de texto começando com "registerList". Não
 * assume o nome da tabela nem a região.
 */
class AndroidSqliteRows(private val context: Context) : SqliteRowSource {

    override fun registerListRows(database: ByteArray, wal: ByteArray?): List<SqliteRow> {
        val dir = File(context.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val dbFile = File(dir, "manifest.sqlite")
            dbFile.writeBytes(database)
            // Com o WAL ao lado e o mesmo nome base, o SQLite aplica as páginas mais novas ao abrir.
            wal?.let { File(dir, "manifest.sqlite-wal").writeBytes(it) }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                val rows = mutableListOf<SqliteRow>()
                for (table in tables) {
                    db.rawQuery("SELECT * FROM \"${table.replace("\"", "\"\"")}\" LIMIT 5000", null).use { c ->
                        while (c.moveToNext()) {
                            val values = (0 until c.columnCount).mapNotNull { valueOf(c, it) }
                            val label = values.firstNotNullOfOrNull { v ->
                                String(v, Charsets.ISO_8859_1).takeIf { it.startsWith("registerList") }
                            } ?: continue
                            rows += SqliteRow(label, values)
                        }
                    }
                }
                return rows
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun valueOf(c: Cursor, column: Int): ByteArray? = when (c.getType(column)) {
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(column)
        Cursor.FIELD_TYPE_STRING -> c.getString(column).toByteArray(Charsets.UTF_8)
        else -> null
    }
}
