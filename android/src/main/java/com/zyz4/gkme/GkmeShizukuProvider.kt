package com.zyz4.gkme

import android.os.Bundle
import rikka.shizuku.ShizukuProvider

/**
 * 承继 Shizuku 的 [ShizukuProvider]，对 [call] 加同步锁。
 *
 * Shizuku 服务端在 App 进程启动时可能**并发**多次下发 binder（实测同一进程内 4 个 binder
 * 线程同时进入 `sendBinder`）。而 Shizuku 库的 `Shizuku.onBinderReceived` 未做同步，并发调用
 * 会对同一个 binder 重复 `unlinkToDeath`，抛出
 * `java.util.NoSuchElementException: Death link does not exist (NAME_NOT_FOUND)`，并作为
 * 未捕获异常回抛给 Shizuku 服务端，导致 binder 交付失败、授权 / 用户服务绑定状态错乱
 * （表现为「已授权但操作无法完成」）。
 *
 * 这里把整个 `call` 串行化：首个 `sendBinder` 写入 binder 后，其余调用会命中
 * `ShizukuProvider.handleSendBinder` 里的 `Shizuku.pingBinder()` 短路直接返回，从而消除该竞态。
 * 注意：这只是把 provider 的 `call` 排队，`onBinderReceived` 内触发的服务端回调（bindApplication）
 * 走的是另一条 binder 线程，不经过本锁，因此不会死锁。
 */
class GkmeShizukuProvider : ShizukuProvider() {

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        super.call(method, arg, extras)
}
