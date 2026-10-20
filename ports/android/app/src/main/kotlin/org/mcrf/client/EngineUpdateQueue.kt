package org.mcrf.client

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** APK queue files are data only. Execution still requires Android's separately signed install. */
object EngineUpdateQueue {
    private val namePattern=Regex("mcrf-engine-[0-9a-f-]{36}\\.apk")
    fun resolve(directory: File, name: String): File {
        require(name.matches(namePattern)) { "Некорректный файл очереди APK." }
        require(!Files.isSymbolicLink(directory.toPath()))
        val base=directory.canonicalFile
        return File(base,name).also {require(it.canonicalFile.parentFile==base&&!Files.isSymbolicLink(it.toPath()))}
    }
    /** fsync verified bytes before an atomic same-directory rename; never replace a previous queued file. */
    fun copyVerified(source: File, directory: File, expected: EnginePackage, cancelled:()->Boolean={false}): File {
        require(expected.bytes in 1..EnginePackage.MAX_BYTES && expected.sha256.matches(Regex("[0-9a-f]{64}")))
        require(source.isFile&&!Files.isSymbolicLink(source.toPath())&&source.length()==expected.bytes) { "APK в очереди повреждён или удалён." }
        require(!Files.isSymbolicLink(directory.toPath()))
        require(directory.isDirectory||directory.mkdir())
        val target=resolve(directory,"mcrf-engine-${UUID.randomUUID()}.apk")
        val temp=File(target.parentFile,".${target.name}.part")
        require(temp.createNewFile())
        try {
            source.inputStream().use {input->FileOutputStream(temp).use {output->
                EnginePackage.copyVerified(input,output,expected,cancelled,{})
                output.fd.sync()
            }}
            check(!cancelled()) { "Подготовка APK отменена." }
            require(!target.exists()&&temp.renameTo(target)) { "Не удалось сохранить APK в очереди." }
            return target
        } finally { temp.delete() }
    }
}
