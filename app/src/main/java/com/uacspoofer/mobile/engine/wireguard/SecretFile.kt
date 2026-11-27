package com.uacspoofer.mobile.engine.wireguard

import android.content.Context
import com.uacspoofer.mobile.engine.foxy.CredentialKeys
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * A small file encrypted with an AES-GCM key that lives in the Android Keystore (never leaves the
 * device, not in backups: the file is in noBackupFilesDir and backups are off app-wide).
 */
internal class SecretFile(context: Context, name: String, private val alias: String) {
    private val file = File(File(context.applicationContext.noBackupFilesDir, "secrets").apply { mkdirs() }, name)

    @Synchronized
    fun read(): String? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 13) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, CredentialKeys.get(alias, create = false), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    @Synchronized
    fun write(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, CredentialKeys.get(alias, create = true))
        val out = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) { file.writeBytes(out); tmp.delete() }
    }

    @Synchronized
    fun delete() { file.delete() }
}
