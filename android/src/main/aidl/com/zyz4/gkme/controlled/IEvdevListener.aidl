// evdev 事件回调：由 Shizuku 用户服务（shell/root）读取 /dev/input/event* 后回传给 App。
// 所有方法都是 oneway，读取线程不会被 App 进程的调度阻塞。
package com.zyz4.gkme.controlled;

oneway interface IEvdevListener {
    /**
     * 一批原始 evdev 事件。
     *
     * data 为紧凑二进制，每条事件 8 字节（小端）：
     *   type : u16
     *   code : u16
     *   value: i32
     * 与 Linux 的 struct input_event 对应，仅去掉时间戳。
     *
     * @param path 事件来源节点，例如 /dev/input/event11
     */
    void onEvents(String path, in byte[] data);

    /**
     * 读取线程结束或出错。
     *
     * @param errno 0 表示正常停止；负数表示 -errno
     */
    void onCaptureError(String path, int errno);
}
