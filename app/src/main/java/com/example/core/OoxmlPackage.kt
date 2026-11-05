package com.example.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Preserves all ZIP entries (styles, themes, media, charts, macros/vbaProject.bin,
 * slideMasters, relationships, content types, etc.) of an OOXML or ODF package
 * so that editing a document never strips untouched parts.
 */
class OoxmlPackage(
    private val entries: LinkedHashMap<String, ByteArray> = LinkedHashMap()
) {
    fun hasEntry(name: String): Boolean = entries.containsKey(name)

    fun getEntry(name: String): ByteArray? = entries[name]

    fun putEntry(name: String, bytes: ByteArray) {
        entries[name] = bytes
    }

    fun removeEntry(name: String) {
        entries.remove(name)
    }

    fun listEntries(prefix: String = ""): List<String> =
        if (prefix.isEmpty()) entries.keys.toList()
        else entries.keys.filter { it.startsWith(prefix) }

    fun writeTo(outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    fun copy(): OoxmlPackage {
        val cloned = LinkedHashMap<String, ByteArray>(entries.size)
        entries.forEach { (k, v) -> cloned[k] = v.copyOf() }
        return OoxmlPackage(cloned)
    }

    companion object {
        fun readFrom(file: File): OoxmlPackage {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(FileInputStream(file)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return OoxmlPackage(map)
        }

        fun isValidZip(file: File): Boolean {
            return runCatching {
                var count = 0
                ZipInputStream(FileInputStream(file)).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        count++
                        zip.closeEntry()
                        e = zip.nextEntry
                    }
                }
                count > 0
            }.getOrDefault(false)
        }
    }
}
