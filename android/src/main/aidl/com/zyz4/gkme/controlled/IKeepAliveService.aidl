// 被控端保活的用户服务接口。
// 该接口的 Stub 由 Shizuku 在 shell/root 进程中实例化，App 进程通过 Binder 调用。
// shell/root 身份可以执行 deviceidle / am 系统命令，从而把本应用加入 Doze（电池优化）
// 白名单并把待机桶置为 active，降低 WiFi 被控端在后台被系统限制或清理的概率。
package com.zyz4.gkme.controlled;

interface IKeepAliveService {
    /**
     * 启用保活：把 [packageName] 加入 Doze/电池优化白名单，并把待机桶设为 active。
     * 返回 0 表示白名单写入成功（待机桶为 best-effort），-1 表示白名单写入失败，
     * -2 表示包名非法。
     */
    int enable(String packageName) = 1;

    /** 撤销保活：把 [packageName] 从 Doze/电池优化白名单移除。返回 0 成功，-1 失败，-2 包名非法。 */
    int disable(String packageName) = 2;

    /**
     * 用户服务退出（Shizuku 约定的事务号，见 Shizuku-API 文档：
     * "The transaction code for that method is 16777115 (use 16777114 in aidl)"）。
     */
    void exitService() = 16777114;
}
