package com.hmgcarapp.crossdrop.app

import com.hmgcarapp.crossdrop.domain.FileFormat
import com.hmgcarapp.crossdrop.domain.FileRules
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * 받은 파일의 저장소(앱 전용 디렉터리 `filesDir/crossdrop-inbox`; 외부 저장소·미디어 라이브러리에 쓰지 않는다).
 * 저장 이름은 항상 앱이 만든다(`f_<무작위 16자리 16진>.<확장자>`): 업로드한 사람이 정한 파일명·경로는 어디에도 쓰이지 않는다.
 * 읽기·삭제는 이 형식의 이름만 받는다(저장 상태 JSON 이 변조돼도 디렉터리 밖을 건드리지 못한다).
 */
class FileVault(private val dir: File, private val random: () -> ByteArray = { ByteArray(8).also { SecureRandom().nextBytes(it) } }) {
    init { dir.mkdirs() }

    fun save(format: FileFormat, data: ByteArray): String {
        val name = "f_" + random().joinToString("") { "%02x".format(it.toInt() and 0xFF) } + "." + format.ext
        require(FileRules.isSafeLocalName(name))
        val tmp = File(dir, "$name.tmp")
        try {
            FileOutputStream(tmp).use { it.write(data); it.flush(); it.fd.sync() }
            check(tmp.renameTo(File(dir, name))) { "rename 실패" }
        } finally { tmp.delete() }
        return name
    }

    fun resolve(name: String?): File? = if (FileRules.isSafeLocalName(name)) File(dir, name!!).takeIf { it.isFile } else null

    fun delete(name: String?): Boolean = if (FileRules.isSafeLocalName(name)) File(dir, name!!).let { !it.exists() || it.delete() } else true

    /** 디렉터리의 모든 파일(임시 파일 포함)을 지운다. 하나도 남지 않으면 true. */
    fun purgeAll(): Boolean {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return dir.listFiles().isNullOrEmpty()
    }

    /** [keep] 에 없는 파일(비정상 종료로 남은 고아·임시 파일)을 지운다. 지운 개수. */
    fun pruneOrphans(keep: Set<String>): Int {
        var n = 0
        dir.listFiles()?.forEach { if (it.name !in keep && it.delete()) n++ }
        return n
    }

    fun names(): Set<String> = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
}
