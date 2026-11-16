package org.mcrf.client

import java.util.zip.ZipFile

internal object EngineArchivePolicy {
    fun requireCatalogVersion(actual:Long,expected:Long?) {
        require(expected==null||actual==expected) {
            "Версия APK движка ($actual) не совпадает с каталогом ($expected). Установка отменена."
        }
    }

    /** Check every shipped ABI, not only the ABI selected on this device. */
    fun requireElfLibraries(zip:ZipFile) {
        zip.entries().asSequence().filter { !it.isDirectory&&it.name.startsWith("lib/") }.forEach { entry ->
            val parts=entry.name.split('/')
            require(parts.size==3) { "Неверный путь нативной библиотеки: ${entry.name}." }
            val header=ByteArray(64)
            val count=zip.getInputStream(entry).use { input ->
                var size=0
                while(size<header.size) {
                    val n=input.read(header,size,header.size-size)
                    if(n<0)break
                    require(n>0) { "Не удалось прочитать ELF: ${entry.name}." }
                    size+=n
                }
                size
            }
            requireElf(parts[1],header.copyOf(count),entry.name)
        }
    }

    fun requireElf(abi:String,header:ByteArray,name:String=abi) {
        val expected=when(abi) {
            "arm64-v8a"->2 to 183 // ELFCLASS64, EM_AARCH64
            "armeabi-v7a"->1 to 40 // ELFCLASS32, EM_ARM
            "x86_64"->2 to 62 // ELFCLASS64, EM_X86_64
            else->throw IllegalArgumentException("Неизвестная ABI библиотеки: $abi.")
        }
        require(header.size>=20&&header[0]==0x7f.toByte()&&header[1]=='E'.code.toByte()&&
            header[2]=='L'.code.toByte()&&header[3]=='F'.code.toByte()) { "Не ELF-библиотека: $name." }
        fun byte(i:Int)=header[i].toInt() and 255
        val machine=byte(18) or (byte(19) shl 8)
        require(byte(4)==expected.first&&byte(5)==1&&byte(6)==1&&machine==expected.second&&
            header.size>=(if(expected.first==2)64 else 52)) { "ELF-библиотека $name не соответствует ABI $abi (класс или архитектура)." }
    }
}
