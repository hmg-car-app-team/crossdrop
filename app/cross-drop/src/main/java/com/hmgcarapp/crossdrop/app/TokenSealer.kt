package com.hmgcarapp.crossdrop.app

import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import java.security.Key
import javax.crypto.spec.GCMParameterSpec

/** 연결 토큰 한 값을 감싸는 도구. 값(키·토큰)은 어떤 로그에도 남기지 않는다. */
interface TokenSealer {
    /** 평문 -> "enc:v1:" 로 시작하는 문자열. 실패하면 예외(호출부는 평문으로 쓰지 않는다). */
    fun seal(plain: String): String

    /** 실패(키 소실·무효화·손상·형식 오류)는 null. 평문 폴백은 없다. */
    fun open(sealed: String): String?

    /** 백그라운드 스레드에서 키를 미리 준비한다(메인 스레드 Keystore 호출 방지). 실패해도 예외를 내지 않는다. */
    fun warmUp() {}
}

object TokenFormat {
    const val PREFIX = "enc:v1:"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
}

/** AES/GCM/NoPadding 봉투: PREFIX + Base64(IV 12바이트 || 암호문+태그). 키 공급만 바꿔 JVM 에서도 같은 코드를 시험한다. */
open class GcmTokenSealer(
    private val keyForSeal: () -> Key,
    private val keyForOpen: () -> Key?,
) : TokenSealer {
    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyForSeal())
        c.updateAAD(AAD)
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        check(iv.size == TokenFormat.IV_BYTES)
        return TokenFormat.PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    }

    override fun open(sealed: String): String? = try {
        if (!sealed.startsWith(TokenFormat.PREFIX)) null else {
            val raw = Base64.getDecoder().decode(sealed.substring(TokenFormat.PREFIX.length))
            if (raw.size <= TokenFormat.IV_BYTES + TokenFormat.TAG_BITS / 8) null else {
                val key = keyForOpen()
                if (key == null) null else {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TokenFormat.TAG_BITS, raw, 0, TokenFormat.IV_BYTES))
                    c.updateAAD(AAD)
                    String(c.doFinal(raw, TokenFormat.IV_BYTES, raw.size - TokenFormat.IV_BYTES), Charsets.UTF_8)
                }
            }
        }
    } catch (t: Throwable) { null }

    private companion object { val AAD = "crossdrop-token-v1".toByteArray(Charsets.UTF_8) }
}

/** Android Keystore 기반(얇게 유지). 키는 별칭 고정·내보내기 불가·사용자 인증 불필요. StrongBox 는 쓰지 않는다. */
class AndroidKeystoreSealer : GcmTokenSealer({ obtain(create = true)!! }, { obtain(create = false) }) {
    override fun warmUp() { runCatching { obtain(create = true) } }

    private companion object {
        const val ALIAS = "crossdrop_token_key_v1"
        @Volatile var cached: Key? = null
        val lock = Any()

        fun obtain(create: Boolean): Key? {
            cached?.let { return it }
            synchronized(lock) {
                cached?.let { return it }
                check(Looper.myLooper() != Looper.getMainLooper()) { "keystore on main thread" }
                try {
                    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    var key = (ks.getKey(ALIAS, null) as? Key)
                    if (key == null && create) {
                        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setUserAuthenticationRequired(false)
                            .build()
                        key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
                    }
                    cached = key
                    return key
                } catch (t: Throwable) {
                    cached = null
                    throw t
                }
            }
        }
    }
}
