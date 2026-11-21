/*
 * evdev 只读采集器：运行在 Shizuku UserService（shell/root）进程。
 *
 * 该进程属于 shell 的 input 组，可以直接打开 /dev/input/event*，因此即使 GKME 不在前台、
 * Activity 收不到 dispatchKeyEvent，也仍然能拿到实体手柄的原始事件。
 *
 * 设计要点：
 *  - 事件读取在 Java/Kotlin 线程里循环调用 nativeEvdevRead，native 只负责 open/poll/read
 *    与紧凑编码，避免 JNI 回调线程的生命周期问题；
 *  - 每条事件编码为 8 字节（type u16、code u16、value i32，小端），Binder 批量回传；
 *  - nativeEvdevList 返回设备描述，App 侧据此匹配目标手柄并过滤 GKME 自建的虚拟手柄。
 */
#include <jni.h>

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#ifndef INPUT_PROP_MAX
#define INPUT_PROP_MAX 0x1f
#endif

#define LOG_TAG "GKME_Evdev"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define EVDEV_PATH_MAX 256
#define EVDEV_STRING_MAX 256
#define EVDEV_MAX_EVENTS 64
#define EVDEV_DESC_MAX 1600

/** 判断某个 capability 位是否置位。 */
static int test_bit(const uint8_t *bits, int bit) {
    return (bits[bit >> 3] >> (bit & 7)) & 1;
}

/**
 * 读取 EVIOCGNAME / EVIOCGPHYS / EVIOCGUNIQ 这类字符串型 ioctl。
 * 把 '|' 替换成 '_'，保证描述串可以安全地用 '|' 分割。
 */
static void copy_ioctl_string(int fd, unsigned long request, char *out, size_t cap) {
    char tmp[EVDEV_STRING_MAX];
    memset(tmp, 0, sizeof(tmp));
    out[0] = '\0';
    if (ioctl(fd, request, tmp) < 0) return;
    size_t limit = cap - 1;
    if (limit > sizeof(tmp) - 1) limit = sizeof(tmp) - 1;
    size_t i = 0;
    for (; i < limit; i++) {
        char c = tmp[i];
        if (c == '\0') break;
        out[i] = (c == '|') ? '_' : c;
    }
    // 必须在实际长度处补 NUL：out 是未初始化的栈缓冲，不能只依赖 out[limit]。
    out[i] = '\0';
}

/** 生成单个 evdev 节点的描述串；失败返回 NULL。 */
static char *build_descriptor(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;

    char name[EVDEV_STRING_MAX];
    char phys[EVDEV_STRING_MAX];
    char uniq[EVDEV_STRING_MAX];
    copy_ioctl_string(fd, EVIOCGNAME(sizeof(name)), name, sizeof(name));
    copy_ioctl_string(fd, EVIOCGPHYS(sizeof(phys)), phys, sizeof(phys));
    copy_ioctl_string(fd, EVIOCGUNIQ(sizeof(uniq)), uniq, sizeof(uniq));

    struct input_id id;
    memset(&id, 0, sizeof(id));
    if (ioctl(fd, EVIOCGID, &id) < 0) {
        close(fd);
        return NULL;
    }

    uint8_t key_bits[(KEY_MAX + 7) / 8];
    uint8_t abs_bits[(ABS_MAX + 7) / 8];
    uint8_t prop_bits[(INPUT_PROP_MAX + 7) / 8];
    memset(key_bits, 0, sizeof(key_bits));
    memset(abs_bits, 0, sizeof(abs_bits));
    memset(prop_bits, 0, sizeof(prop_bits));
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits);
    ioctl(fd, EVIOCGPROP(sizeof(prop_bits)), prop_bits);

    int is_gamepad = test_bit(key_bits, BTN_GAMEPAD) ||
                     test_bit(key_bits, BTN_SOUTH) ||
                     test_bit(key_bits, BTN_A) ||
                     (test_bit(abs_bits, ABS_X) && test_bit(abs_bits, ABS_Y) &&
                      test_bit(key_bits, BTN_TRIGGER));

    int is_touchpad = test_bit(prop_bits, INPUT_PROP_POINTER) &&
                      test_bit(abs_bits, ABS_MT_POSITION_X) &&
                      test_bit(abs_bits, ABS_MT_POSITION_Y);

    int is_motion = test_bit(prop_bits, INPUT_PROP_ACCELEROMETER) ||
                    (test_bit(abs_bits, ABS_RX) && test_bit(abs_bits, ABS_RY) &&
                     test_bit(abs_bits, ABS_RZ) && !is_gamepad);

    int flags = (is_gamepad ? 1 : 0) | (is_touchpad ? 2 : 0) | (is_motion ? 4 : 0);

    static const int abs_codes[] = {
            ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ,
            ABS_HAT0X, ABS_HAT0Y, ABS_GAS, ABS_BRAKE, ABS_THROTTLE,
    };

    char abs_spec[512];
    size_t abs_len = 0;
    abs_spec[0] = '\0';
    for (size_t i = 0; i < sizeof(abs_codes) / sizeof(abs_codes[0]); i++) {
        int code = abs_codes[i];
        if (!test_bit(abs_bits, code)) continue;
        struct input_absinfo info;
        memset(&info, 0, sizeof(info));
        if (ioctl(fd, EVIOCGABS(code), &info) < 0) continue;
        int written = snprintf(abs_spec + abs_len, sizeof(abs_spec) - abs_len,
                               "%s%d:%d:%d", abs_len == 0 ? "" : ",", code,
                               info.minimum, info.maximum);
        if (written <= 0) break;
        abs_len += (size_t) written;
        if (abs_len >= sizeof(abs_spec)) break;
    }

    char *desc = (char *) malloc(EVDEV_DESC_MAX);
    if (desc != NULL) {
        snprintf(desc, EVDEV_DESC_MAX, "%s|%s|%s|%s|%d|%d|%d|%d|%s",
                 path, name, phys, uniq, id.bustype, id.vendor, id.product,
                 flags, abs_spec);
    }
    close(fd);
    return desc;
}

JNIEXPORT jobjectArray JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeEvdevList(
        JNIEnv *env, jclass clazz) {
    (void) clazz;

    DIR *dir = opendir("/dev/input");
    if (dir == NULL) {
        LOGW("无法打开 /dev/input: %s", strerror(errno));
        return NULL;
    }

    char **descs = NULL;
    int count = 0;
    int capacity = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;
        char path[EVDEV_PATH_MAX];
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        char *desc = build_descriptor(path);
        if (desc == NULL) continue;

        if (count == capacity) {
            int next = capacity == 0 ? 8 : capacity * 2;
            char **grown = (char **) realloc(descs, (size_t) next * sizeof(char *));
            if (grown == NULL) {
                free(desc);
                break;
            }
            descs = grown;
            capacity = next;
        }
        descs[count++] = desc;
    }
    closedir(dir);

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) {
        for (int i = 0; i < count; i++) free(descs[i]);
        free(descs);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, count, string_class, NULL);
    if (result != NULL) {
        for (int i = 0; i < count; i++) {
            jstring item = (*env)->NewStringUTF(env, descs[i]);
            if (item == NULL) break;
            (*env)->SetObjectArrayElement(env, result, i, item);
            (*env)->DeleteLocalRef(env, item);
        }
    }

    for (int i = 0; i < count; i++) free(descs[i]);
    free(descs);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeEvdevOpen(
        JNIEnv *env, jclass clazz, jstring jpath) {
    (void) clazz;
    if (jpath == NULL) return -EINVAL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -ENOMEM;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (fd < 0) return -err;
    return fd;
}

/**
 * 最多等待 timeout_ms 毫秒，然后读取当前已就绪的事件并编码到 out。
 *
 * @return 写入的字节数（8 的倍数）；0 表示超时；负数表示 -errno。
 */
JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeEvdevRead(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray out, jint timeout_ms) {
    (void) clazz;
    if (fd < 0 || out == NULL) return -EBADF;

    jsize out_len = (*env)->GetArrayLength(env, out);
    int max_events = (int) (out_len / 8);
    if (max_events <= 0) return 0;
    if (max_events > EVDEV_MAX_EVENTS) max_events = EVDEV_MAX_EVENTS;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int poll_result;
    do {
        poll_result = poll(&pfd, 1, timeout_ms);
    } while (poll_result < 0 && errno == EINTR);

    if (poll_result == 0) return 0;
    if (poll_result < 0) return -errno;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -ENODEV;

    struct input_event events[EVDEV_MAX_EVENTS];
    ssize_t read_bytes;
    do {
        read_bytes = read(fd, events,
                          sizeof(struct input_event) * (size_t) max_events);
    } while (read_bytes < 0 && errno == EINTR);

    if (read_bytes < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        return -errno;
    }

    int count = (int) (read_bytes / (ssize_t) sizeof(struct input_event));
    if (count <= 0) return 0;

    uint8_t buffer[EVDEV_MAX_EVENTS * 8];
    for (int i = 0; i < count; i++) {
        uint16_t type = events[i].type;
        uint16_t code = events[i].code;
        int32_t value = events[i].value;
        uint8_t *p = buffer + i * 8;
        p[0] = (uint8_t) (type & 0xff);
        p[1] = (uint8_t) ((type >> 8) & 0xff);
        p[2] = (uint8_t) (code & 0xff);
        p[3] = (uint8_t) ((code >> 8) & 0xff);
        p[4] = (uint8_t) (value & 0xff);
        p[5] = (uint8_t) ((value >> 8) & 0xff);
        p[6] = (uint8_t) ((value >> 16) & 0xff);
        p[7] = (uint8_t) ((value >> 24) & 0xff);
    }

    (*env)->SetByteArrayRegion(env, out, 0, count * 8, (const jbyte *) buffer);
    return count * 8;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeEvdevClose(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) close(fd);
}
