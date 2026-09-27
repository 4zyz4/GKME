/*
 * GKME 被控端虚拟手柄 —— 直接操作 Linux /dev/uinput。
 *
 * 该库运行在 Shizuku UserService 进程（shell/root 身份）中，因为普通 App 进程
 * 无法打开 /dev/uinput。实现参考 starcore_gamepad_reverse.md：
 *   - BUS_VIRTUAL + vendor=0x045E(Microsoft) + product=0x02FD 伪装成 Xbox 手柄；
 *   - 按键位沿用 XInput wButtons 掩码，再翻译成 Linux BTN_* / KEY_*；
 *   - 左摇杆 ABS_X/ABS_Y，右摇杆 ABS_Z/ABS_RZ，扳机 ABS_BRAKE/ABS_GAS，
 *     十字键 ABS_HAT0X/ABS_HAT0Y；
 *   - 每次状态变化写 11 个按键事件 + 8 个轴事件 + 1 个 EV_SYN。
 */

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define GKME_UINPUT_IOCTL_BASE 'U'

/* ── ioctl 常量（与内核 uinput.h 一致，避免依赖 NDK 头文件版本）────────── */
#define GKME_UI_DEV_CREATE   _IO(GKME_UINPUT_IOCTL_BASE, 1)
#define GKME_UI_DEV_DESTROY  _IO(GKME_UINPUT_IOCTL_BASE, 2)
#define GKME_UI_SET_EVBIT    _IOW(GKME_UINPUT_IOCTL_BASE, 100, int)
#define GKME_UI_SET_KEYBIT   _IOW(GKME_UINPUT_IOCTL_BASE, 101, int)
#define GKME_UI_SET_ABSBIT   _IOW(GKME_UINPUT_IOCTL_BASE, 103, int)
#define GKME_UI_SET_FFBIT    _IOW(GKME_UINPUT_IOCTL_BASE, 107, int)

struct gkme_input_id {
    uint16_t bustype;
    uint16_t vendor;
    uint16_t product;
    uint16_t version;
};

struct gkme_uinput_setup {
    struct gkme_input_id id;
    char name[80];
    uint32_t ff_effects_max;
};

struct gkme_abs_setup {
    uint16_t code;
    struct input_absinfo absinfo;
};

#define GKME_UI_DEV_SETUP  _IOW(GKME_UINPUT_IOCTL_BASE, 3, struct gkme_uinput_setup)
#define GKME_UI_ABS_SETUP  _IOW(GKME_UINPUT_IOCTL_BASE, 4, struct gkme_abs_setup)

/* EV_UINPUT 及其 code 用于 FF 上传/擦除，NDK 头文件可能缺失。 */
#ifndef EV_UINPUT
#define EV_UINPUT 0x0101
#endif
#define GKME_UI_FF_UPLOAD 1
#define GKME_UI_FF_ERASE  2

struct gkme_ff_upload {
    uint32_t request_id;
    int32_t retval;
    struct ff_effect effect;
};

struct gkme_ff_erase {
    uint32_t request_id;
    int32_t retval;
    uint32_t effect_id;
};

#define GKME_UI_BEGIN_FF_UPLOAD _IOWR(GKME_UINPUT_IOCTL_BASE, 200, struct gkme_ff_upload)
#define GKME_UI_END_FF_UPLOAD   _IOW(GKME_UINPUT_IOCTL_BASE, 201, struct gkme_ff_upload)
#define GKME_UI_BEGIN_FF_ERASE  _IOWR(GKME_UINPUT_IOCTL_BASE, 202, struct gkme_ff_erase)
#define GKME_UI_END_FF_ERASE    _IOW(GKME_UINPUT_IOCTL_BASE, 203, struct gkme_ff_erase)

/* 兼容旧 NDK：缺少数值。
 * 注意不得使用 #ifndef KEY_BACK，否则与 input.h 冲突；这里直接用字面量。 */
#define GKME_BTN_SOUTH 0x130
#define GKME_BTN_EAST  0x131
#define GKME_BTN_NORTH 0x133
#define GKME_BTN_WEST  0x134
#define GKME_BTN_TL    0x136
#define GKME_BTN_TR    0x137
#define GKME_BTN_BACK  0x09E
#define GKME_BTN_START 0x13B
#define GKME_BTN_MODE  0x0AC
#define GKME_BTN_THUMBL 0x13D
#define GKME_BTN_THUMBR 0x13E

#define GKME_MAX_FF 16
#define GKME_EVENT_COUNT 20 /* 11 key + 8 abs + 1 syn */

typedef struct {
    int fd;
    volatile int running;
    pthread_t thread;
    pthread_mutex_t lock;
    int left;  /* 0..32767 */
    int right; /* 0..32767 */
    unsigned char has_effect[GKME_MAX_FF];
    unsigned char playing[GKME_MAX_FF];
    struct ff_effect effects[GKME_MAX_FF];
} gkme_dev;

static gkme_dev *g_devs[256];
static pthread_mutex_t g_devs_lock = PTHREAD_MUTEX_INITIALIZER;

static const int g_key_codes[11] = {
    GKME_BTN_SOUTH, GKME_BTN_EAST, GKME_BTN_NORTH, GKME_BTN_WEST,
    GKME_BTN_TL, GKME_BTN_TR, GKME_BTN_BACK, GKME_BTN_START,
    GKME_BTN_MODE, GKME_BTN_THUMBL, GKME_BTN_THUMBR,
};

/* XInput wButtons 掩码（与 starcore 完全一致）。 */
static const int g_key_masks[11] = {
    0x00001000, 0x00002000, 0x00004000, 0x00008000,
    0x00000100, 0x00000200, 0x00000020, 0x00000010,
    0x00000400, 0x00000040, 0x00000080,
};

static const struct {
    int code;
    int min;
    int max;
    int fuzz;
    int flat;
} g_axes[8] = {
    {ABS_X, -32768, 32767, 1024, 0},
    {ABS_Y, -32768, 32767, 1024, 0},
    {ABS_Z, -32768, 32767, 1024, 0},
    {ABS_RZ, -32768, 32767, 1024, 0},
    {ABS_BRAKE, 0, 255, 0, 0},
    {ABS_GAS, 0, 255, 0, 0},
    {ABS_HAT0X, -1, 1, 0, 0},
    {ABS_HAT0Y, -1, 1, 0, 0},
};

static int gkme_clamp_s16(int v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return v;
}

static void gkme_store_dev(int fd, gkme_dev *dev) {
    if (fd < 0 || fd >= 256) return;
    pthread_mutex_lock(&g_devs_lock);
    g_devs[fd] = dev;
    pthread_mutex_unlock(&g_devs_lock);
}

static gkme_dev *gkme_get_dev(int fd) {
    if (fd < 0 || fd >= 256) return NULL;
    pthread_mutex_lock(&g_devs_lock);
    gkme_dev *dev = g_devs[fd];
    pthread_mutex_unlock(&g_devs_lock);
    return dev;
}

static void gkme_recompute_rumble(gkme_dev *dev) {
    int left = 0, right = 0;
    for (int i = 0; i < GKME_MAX_FF; i++) {
        if (!dev->playing[i] || !dev->has_effect[i]) continue;
        if (dev->effects[i].type != FF_RUMBLE) continue;
        int strong = dev->effects[i].u.rumble.strong_magnitude;
        int weak = dev->effects[i].u.rumble.weak_magnitude;
        if (strong > left) left = strong;
        if (weak > right) right = weak;
    }
    pthread_mutex_lock(&dev->lock);
    dev->left = left;
    dev->right = right;
    pthread_mutex_unlock(&dev->lock);
}

static void gkme_handle_upload(gkme_dev *dev, uint32_t request_id) {
    struct gkme_ff_upload up;
    memset(&up, 0, sizeof(up));
    up.request_id = request_id;
    if (ioctl(dev->fd, GKME_UI_BEGIN_FF_UPLOAD, &up) == 0) {
        int id = up.effect.id;
        if (id >= 0 && id < GKME_MAX_FF) {
            dev->effects[id] = up.effect;
            dev->has_effect[id] = 1;
        }
        up.retval = 0;
        ioctl(dev->fd, GKME_UI_END_FF_UPLOAD, &up);
    }
}

static void gkme_handle_erase(gkme_dev *dev, uint32_t request_id) {
    struct gkme_ff_erase er;
    memset(&er, 0, sizeof(er));
    er.request_id = request_id;
    if (ioctl(dev->fd, GKME_UI_BEGIN_FF_ERASE, &er) == 0) {
        uint32_t id = er.effect_id;
        if (id < GKME_MAX_FF) {
            dev->has_effect[id] = 0;
            dev->playing[id] = 0;
        }
        er.retval = 0;
        ioctl(dev->fd, GKME_UI_END_FF_ERASE, &er);
    }
}

static void *gkme_ff_thread(void *arg) {
    gkme_dev *dev = (gkme_dev *)arg;
    struct input_event ev;
    while (dev->running) {
        ssize_t n = read(dev->fd, &ev, sizeof(ev));
        if (n == (ssize_t)sizeof(ev)) {
            if (ev.type == EV_UINPUT) {
                if (ev.code == GKME_UI_FF_UPLOAD) gkme_handle_upload(dev, (uint32_t)ev.value);
                else if (ev.code == GKME_UI_FF_ERASE) gkme_handle_erase(dev, (uint32_t)ev.value);
            } else if (ev.type == EV_FF) {
                int id = ev.code;
                if (id >= 0 && id < GKME_MAX_FF) {
                    dev->playing[id] = ev.value != 0 ? 1 : 0;
                    gkme_recompute_rumble(dev);
                }
            }
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            usleep(1000);
            continue;
        }
        if (n < 0) {
            usleep(2000);
            continue;
        }
        if (n == 0) usleep(1000);
    }
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeCreate(JNIEnv *env, jclass clazz,
                                                              jint rumbleEnabled) {
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;

    if (ioctl(fd, GKME_UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, GKME_UI_SET_EVBIT, EV_ABS) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    for (int i = 0; i < 11; i++) {
        if (ioctl(fd, GKME_UI_SET_KEYBIT, g_key_codes[i]) < 0) {
            int e = errno;
            close(fd);
            return -e;
        }
    }

    for (int i = 0; i < 8; i++) {
        struct gkme_abs_setup setup;
        memset(&setup, 0, sizeof(setup));
        setup.code = (uint16_t)g_axes[i].code;
        setup.absinfo.value = 0;
        setup.absinfo.minimum = g_axes[i].min;
        setup.absinfo.maximum = g_axes[i].max;
        setup.absinfo.fuzz = g_axes[i].fuzz;
        setup.absinfo.flat = g_axes[i].flat;
        setup.absinfo.resolution = 0;
        if (ioctl(fd, GKME_UI_ABS_SETUP, &setup) < 0 ||
            ioctl(fd, GKME_UI_SET_ABSBIT, g_axes[i].code) < 0) {
            int e = errno;
            close(fd);
            return -e;
        }
    }

    uint32_t ff_max = 0;
    if (rumbleEnabled) {
        if (ioctl(fd, GKME_UI_SET_EVBIT, EV_FF) == 0 &&
            ioctl(fd, GKME_UI_SET_FFBIT, FF_RUMBLE) == 0) {
            ioctl(fd, GKME_UI_SET_FFBIT, FF_GAIN);
            ff_max = GKME_MAX_FF;
        }
    }

    struct gkme_uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = 0x0006; /* BUS_VIRTUAL */
    setup.id.vendor = 0x045E;  /* Microsoft */
    setup.id.product = 0x02FD; /* Xbox 手柄 */
    setup.id.version = 0x0001;
    snprintf(setup.name, sizeof(setup.name), "%s", "GKME Remote Gamepad");
    setup.ff_effects_max = ff_max;

    if (ioctl(fd, GKME_UI_DEV_SETUP, &setup) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }
    if (ioctl(fd, GKME_UI_DEV_CREATE) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    gkme_dev *dev = (gkme_dev *)calloc(1, sizeof(gkme_dev));
    if (!dev) {
        ioctl(fd, GKME_UI_DEV_DESTROY);
        close(fd);
        return -ENOMEM;
    }
    dev->fd = fd;
    dev->running = ff_max > 0 ? 1 : 0;
    pthread_mutex_init(&dev->lock, NULL);
    gkme_store_dev(fd, dev);
    if (dev->running) {
        pthread_create(&dev->thread, NULL, gkme_ff_thread, dev);
    }
    return fd;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeWrite(
        JNIEnv *env, jclass clazz, jint fd, jint buttons, jint leftTrigger,
        jint rightTrigger, jint leftX, jint leftY, jint rightX, jint rightY) {
    gkme_dev *dev = gkme_get_dev(fd);
    if (!dev || dev->fd < 0) return;

    struct input_event events[GKME_EVENT_COUNT];
    memset(events, 0, sizeof(events));
    int n = 0;

    for (int i = 0; i < 11; i++) {
        events[n].type = EV_KEY;
        events[n].code = (uint16_t)g_key_codes[i];
        events[n].value = (buttons & g_key_masks[i]) ? 1 : 0;
        n++;
    }

    int lx = gkme_clamp_s16(leftX);
    int ly = gkme_clamp_s16(-leftY);
    int rx = gkme_clamp_s16(rightX);
    int rz = gkme_clamp_s16(-rightY);
    int brake = leftTrigger < 0 ? 0 : (leftTrigger > 255 ? 255 : leftTrigger);
    int gas = rightTrigger < 0 ? 0 : (rightTrigger > 255 ? 255 : rightTrigger);

    int hatX = ((buttons >> 3) & 1) - ((buttons >> 2) & 1);
    int hatY = ((buttons >> 1) & 1) - (buttons & 1);

    int values[8] = {lx, ly, rx, rz, brake, gas, hatX, hatY};
    for (int i = 0; i < 8; i++) {
        events[n].type = EV_ABS;
        events[n].code = (uint16_t)g_axes[i].code;
        events[n].value = values[i];
        n++;
    }

    events[n].type = EV_SYN;
    events[n].code = SYN_REPORT;
    events[n].value = 0;
    n++;

    size_t total = (size_t)n * sizeof(struct input_event);
    const char *p = (const char *)events;
    size_t written = 0;
    while (written < total) {
        ssize_t w = write(dev->fd, p + written, total - written);
        if (w < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(200);
                continue;
            }
            break;
        }
        written += (size_t)w;
    }
}

JNIEXPORT jlong JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeRumble(JNIEnv *env, jclass clazz,
                                                              jint fd) {
    gkme_dev *dev = gkme_get_dev(fd);
    if (!dev) return 0;
    int left, right;
    pthread_mutex_lock(&dev->lock);
    left = dev->left;
    right = dev->right;
    pthread_mutex_unlock(&dev->lock);
    return ((jlong)(left & 0xFFFF) << 16) | (jlong)(right & 0xFFFF);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeDestroy(JNIEnv *env, jclass clazz,
                                                               jint fd) {
    gkme_dev *dev = gkme_get_dev(fd);
    if (!dev) return;
    gkme_store_dev(fd, NULL);
    dev->running = 0;
    if (dev->thread) {
        pthread_join(dev->thread, NULL);
        dev->thread = 0;
    }
    if (dev->fd >= 0) {
        ioctl(dev->fd, GKME_UI_DEV_DESTROY);
        close(dev->fd);
        dev->fd = -1;
    }
    pthread_mutex_destroy(&dev->lock);
    free(dev);
}
