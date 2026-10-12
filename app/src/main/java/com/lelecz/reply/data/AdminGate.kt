package com.lelecz.reply.data

import java.security.MessageDigest

/**
 * 管理员密码门禁：包内只存 SHA-256(盐+密码) 哈希，明文密码不进 APK。
 * 连续输错 5 次锁定 60 秒，防脚本瞎猜（进程内计数，重启即清）。
 */
object AdminGate {

    private const val SALT = "n1t14n_g@te!"
    private const val HASH = "f63a7a3a356272ab572c712572bb88a89bc6801d2302d1b20da0aec9ddc728b4"

    private const val MAX_FAIL = 5
    private const val LOCK_MS = 60_000L

    private var fails = 0
    private var lockedUntil = 0L

    /** 当前是否处于锁定中 */
    fun isLocked(): Boolean = System.currentTimeMillis() < lockedUntil

    /** 剩余锁定秒数（未锁定返回 0） */
    fun remainingLockSec(): Long {
        val left = lockedUntil - System.currentTimeMillis()
        return if (left <= 0) 0 else left / 1000 + 1
    }

    /** 校验密码；锁定期间直接失败 */
    fun check(password: String): Boolean {
        if (isLocked()) return false
        val h = MessageDigest.getInstance("SHA-256")
            .digest((SALT + password).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (h == HASH) {
            fails = 0
            return true
        }
        fails++
        if (fails >= MAX_FAIL) {
            lockedUntil = System.currentTimeMillis() + LOCK_MS
            fails = 0
        }
        return false
    }
}
