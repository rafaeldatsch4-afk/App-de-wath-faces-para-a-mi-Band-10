package io.github.rafaeldatsch4afk.mibandfaces.testutil

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Monta um .rpk mínimo (ZIP com manifest.json, app.js e a página inicial) para os testes. */
object RpkBuilder {
    fun manifest(
        packageName: String = "com.exemplo.calc",
        designWidth: Int = 212,
        deviceType: String = "watch",
    ) = """
        {
          "package": "$packageName",
          "name": "Calculadora",
          "versionName": "1.0.1",
          "versionCode": 3,
          "icon": "/common/logo.png",
          "deviceTypeList": ["$deviceType"],
          "features": [{"name": "system.prompt"}, {"name": "system.vibrator"}],
          "config": {"designWidth": $designWidth},
          "router": {"entry": "pages/index", "pages": {"pages/index": {"component": "index"}}}
        }
    """.trimIndent()

    fun build(
        manifest: String? = manifest(),
        extra: Map<String, ByteArray> = emptyMap(),
        withEntryPage: Boolean = true,
        padding: Int = 3_000,
    ): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        files["META-INF/MANIFEST.MF"] = "Manifest-Version: 1.0\n".toByteArray()
        manifest?.let { files["manifest.json"] = it.toByteArray() }
        files["app.js"] = "export default function(){}".toByteArray()
        if (withEntryPage) files["pages/index/index.js"] = "// página\n".toByteArray()
        files["common/logo.png"] = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        // Conteúdo pouco compressível, para o envio ter várias partes.
        files["common/dados.bin"] = ByteArray(padding).also { java.util.Random(42).nextBytes(it) }
        files.putAll(extra)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
