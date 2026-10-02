/*
 * GKME 被控端虚拟手柄 —— 直接操作 Linux /dev/uinput。
 *
 * 该库运行在 Shizuku UserService 进程（shell/root 身份）中，因为普通 App 进程
 * 无法打开 /dev/uinput。实现参考 starcore_gamepad_reverse.md：
 *   - BUS_VIRTUAL + vendor=0x045E(Microsoft) + product=0x02FD 伪装成 Xbox One S 手柄；
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
#define GKME_UI_SET_RELBIT   _IOW(GKME_UINPUT_IOCTL_BASE, 102, int)
#define GKME_UI_SET_ABSBIT   _IOW(GKME_UINPUT_IOCTL_BASE, 103, int)
#define GKME_UI_SET_FFBIT    _IOW(GKME_UINPUT_IOCTL_BASE, 107, int)

/* EV_REP 值（个别 NDK 头缺失该枚举），用独立宏名避免与内核头冲突。 */
#define GKME_EV_REP 0x14

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
    /* 为 0 时仍暴露 FF 能力，但忽略震动数据（本机模式，避免回环）。 */
    int rumble_enabled;
    unsigned char has_effect[GKME_MAX_FF];
    unsigned char playing[GKME_MAX_FF];
    struct ff_effect effects[GKME_MAX_FF];
} gkme_dev;

static gkme_dev *g_devs[256];
static pthread_mutex_t g_devs_lock = PTHREAD_MUTEX_INITIALIZER;

/* ── 虚拟键盘 / 鼠标（uinput）────────────────────────────────────────────
 * 报文语义对齐 GKME-Windows 的 VirtualKeyboardMouse.cs：
 *   - 键盘为「全量状态」：HID 修饰键位掩码 + HID 键位用法（0x04..），本机记录上
 *     一帧状态，只对发生变化的键位写按下/抬起事件；内核负责去重，并由 EV_REP
 *     自动产生长按重复。
 *   - 鼠标为相对位移 + 滚轮/横向滚轮 + 全量按键掩码，同样只在状态变化时写事件。
 * 键盘与鼠标是两个独立 uinput 设备，与 Windows 端的 keyboard-composite /
 * mouse-composite 两种身份对应。
 */
typedef struct {
    int fd;
    unsigned char keys[256]; /* HID usage -> 当前是否按下 */
    unsigned char mods;      /* HID 修饰键位掩码 */
} gkme_kbd;

typedef struct {
    int fd;
    int buttons;      /* 上一次的鼠标按键掩码 */
    int wheel_rem;    /* 垂直滚轮余量（协议单位，120=1 格），用于传统 REL_WHEEL */
    int pan_rem;      /* 横向滚轮余量（协议单位） */
    int wheel_hi_rem; /* 垂直滚轮余量（协议单位），用于高精度轴换算 */
    int pan_hi_rem;   /* 横向滚轮余量（协议单位），用于高精度轴换算 */
} gkme_ms;

static gkme_kbd *g_kbds[256];
static gkme_ms *g_mice[256];

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
    {ABS_X, -32768, 32767, 0, 0},
    {ABS_Y, -32768, 32767, 0, 0},
    {ABS_Z, -32768, 32767, 0, 0},
    {ABS_RZ, -32768, 32767, 0, 0},
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

    /* 始终暴露 FF 能力：即便本机模式也要在系统/游戏里显示为带震动的设备，
     * 是否把震动数据转发到手机由 rumbleEnabled（存于 dev->rumble_enabled）决定。 */
    uint32_t ff_max = 0;
    if (ioctl(fd, GKME_UI_SET_EVBIT, EV_FF) == 0 &&
        ioctl(fd, GKME_UI_SET_FFBIT, FF_RUMBLE) == 0) {
        ioctl(fd, GKME_UI_SET_FFBIT, FF_GAIN);
        ff_max = GKME_MAX_FF;
    }

    struct gkme_uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = 0x0006; /* BUS_VIRTUAL */
    setup.id.vendor = 0x045E;  /* Microsoft */
    setup.id.product = 0x02FD; /* Xbox One S 手柄 */
    setup.id.version = 0x0001;
    /* 使用被模拟设备的名称（虚拟 Xbox One S）。App 排除自己创建的虚拟手柄时
     * 只依据 vendor/product（SDL 会改写设备名，名称不可靠），见 VirtualGamepad.kt /
     * sdl_bridge.cpp。此处的名称仅用于系统展示。 */
    snprintf(setup.name, sizeof(setup.name), "%s", "Xbox One S Controller");
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
    dev->rumble_enabled = rumbleEnabled ? 1 : 0;
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
    int ly = gkme_clamp_s16(leftY);
    int rx = gkme_clamp_s16(rightX);
    int rz = gkme_clamp_s16(rightY);
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
    if (!dev || !dev->rumble_enabled) return 0;
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

/* ── 虚拟键盘 / 鼠标 实现 ─────────────────────────────────────────────── */

static void gkme_store_kbd(int fd, gkme_kbd *dev) {
    if (fd < 0 || fd >= 256) return;
    pthread_mutex_lock(&g_devs_lock);
    g_kbds[fd] = dev;
    pthread_mutex_unlock(&g_devs_lock);
}

static gkme_kbd *gkme_get_kbd(int fd) {
    if (fd < 0 || fd >= 256) return NULL;
    pthread_mutex_lock(&g_devs_lock);
    gkme_kbd *dev = g_kbds[fd];
    pthread_mutex_unlock(&g_devs_lock);
    return dev;
}

static void gkme_store_ms(int fd, gkme_ms *dev) {
    if (fd < 0 || fd >= 256) return;
    pthread_mutex_lock(&g_devs_lock);
    g_mice[fd] = dev;
    pthread_mutex_unlock(&g_devs_lock);
}

static gkme_ms *gkme_get_ms(int fd) {
    if (fd < 0 || fd >= 256) return NULL;
    pthread_mutex_lock(&g_devs_lock);
    gkme_ms *dev = g_mice[fd];
    pthread_mutex_unlock(&g_devs_lock);
    return dev;
}

/* HID Keyboard/Keypad usage（0x04..）→ Linux input key code。修饰键（0xE0..0xE7）
 * 单独走修饰键位掩码，不在此表内。表值直接写 Linux 常量数值，避免依赖 NDK 头版本。 */
static const struct {
    unsigned short usage;
    unsigned short code;
} g_hid_keymap[] = {
    {0x04, 30},  /* A */            {0x05, 48},  /* B */
    {0x06, 46},  /* C */            {0x07, 32},  /* D */
    {0x08, 18},  /* E */            {0x09, 33},  /* F */
    {0x0A, 34},  /* G */            {0x0B, 35},  /* H */
    {0x0C, 23},  /* I */            {0x0D, 36},  /* J */
    {0x0E, 37},  /* K */            {0x0F, 38},  /* L */
    {0x10, 50},  /* M */            {0x11, 49},  /* N */
    {0x12, 24},  /* O */            {0x13, 25},  /* P */
    {0x14, 16},  /* Q */            {0x15, 19},  /* R */
    {0x16, 31},  /* S */            {0x17, 20},  /* T */
    {0x18, 22},  /* U */            {0x19, 47},  /* V */
    {0x1A, 17},  /* W */            {0x1B, 45},  /* X */
    {0x1C, 21},  /* Y */            {0x1D, 44},  /* Z */
    {0x1E, 2},   /* 1 */            {0x1F, 3},   /* 2 */
    {0x20, 4},   /* 3 */            {0x21, 5},   /* 4 */
    {0x22, 6},   /* 5 */            {0x23, 7},   /* 6 */
    {0x24, 8},   /* 7 */            {0x25, 9},   /* 8 */
    {0x26, 10},  /* 9 */            {0x27, 11},  /* 0 */
    {0x28, 28},  /* Enter */        {0x29, 1},   /* Esc */
    {0x2A, 14},  /* Backspace */    {0x2B, 15},  /* Tab */
    {0x2C, 57},  /* Space */        {0x2D, 12},  /* - */
    {0x2E, 13},  /* = */            {0x2F, 26},  /* [ */
    {0x30, 27},  /* ] */            {0x31, 43},  /* \\ */
    {0x32, 86},  /* 102ND */        {0x33, 39},  /* ; */
    {0x34, 40},  /* ' */            {0x35, 41},  /* ` */
    {0x36, 51},  /* , */            {0x37, 52},  /* . */
    {0x38, 53},  /* / */            {0x39, 58},  /* CapsLock */
    {0x3A, 59},  /* F1 */           {0x3B, 60},  /* F2 */
    {0x3C, 61},  /* F3 */           {0x3D, 62},  /* F4 */
    {0x3E, 63},  /* F5 */           {0x3F, 64},  /* F6 */
    {0x40, 65},  /* F7 */           {0x41, 66},  /* F8 */
    {0x42, 67},  /* F9 */           {0x43, 68},  /* F10 */
    {0x44, 87},  /* F11 */          {0x45, 88},  /* F12 */
    {0x46, 99},  /* PrintScreen */  {0x47, 70},  /* ScrollLock */
    {0x48, 119}, /* Pause */        {0x49, 110}, /* Insert */
    {0x4A, 102}, /* Home */         {0x4B, 104}, /* PageUp */
    {0x4C, 111}, /* Delete */       {0x4D, 107}, /* End */
    {0x4E, 109}, /* PageDown */     {0x4F, 106}, /* Right */
    {0x50, 105}, /* Left */         {0x51, 108}, /* Down */
    {0x52, 103}, /* Up */           {0x53, 69},  /* NumLock */
    {0x54, 98},  /* KP / */         {0x55, 55},  /* KP * */
    {0x56, 74},  /* KP - */         {0x57, 78},  /* KP + */
    {0x58, 96},  /* KP Enter */     {0x59, 79},  /* KP 1 */
    {0x5A, 80},  /* KP 2 */         {0x5B, 81},  /* KP 3 */
    {0x5C, 75},  /* KP 4 */         {0x5D, 76},  /* KP 5 */
    {0x5E, 77},  /* KP 6 */         {0x5F, 71},  /* KP 7 */
    {0x60, 72},  /* KP 8 */         {0x61, 73},  /* KP 9 */
    {0x62, 82},  /* KP 0 */         {0x63, 83},  /* KP . */
    {0x64, 86},  /* 102ND (\\|) */  {0x65, 127}, /* Menu */
    {0x66, 116}, /* Power */        {0x67, 117}, /* KP = */
    {0x68, 183}, /* F13 */          {0x69, 184}, /* F14 */
    {0x6A, 185}, /* F15 */          {0x6B, 186}, /* F16 */
    {0x6C, 187}, /* F17 */          {0x6D, 188}, /* F18 */
    {0x6E, 189}, /* F19 */          {0x6F, 190}, /* F20 */
    {0x70, 191}, /* F21 */          {0x71, 192}, /* F22 */
    {0x72, 193}, /* F23 */          {0x73, 194}, /* F24 */
};

/* HID 修饰键位（bit0=LCtrl..bit7=RGui）→ Linux 键码。 */
static const int g_hid_modmap[8] = {29, 42, 56, 125, 97, 54, 100, 126};

/* 鼠标按键掩码位（bit0=LMB,1=RMB,2=MMB,3=Back,4=Forward）→ Linux BTN_*。 */
static const int g_mouse_btnmap[5] = {272, 273, 274, 275, 276};

/* REL_X=0, REL_Y=1, REL_HWHEEL=6, REL_WHEEL=8, REL_WHEEL_HI_RES=0x0b, REL_HWHEEL_HI_RES=0x0c。 */
#define GKME_REL_X 0
#define GKME_REL_Y 1
#define GKME_REL_HWHEEL 6
#define GKME_REL_WHEEL 8
#define GKME_REL_WHEEL_HI_RES 0x0b
#define GKME_REL_HWHEEL_HI_RES 0x0c

/* 控制器/协议滚轮单位：120 单位 = 1 格（WHEEL_DELTA，与 GKME-Windows 一致）。 */
#define GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT 120

/* 本机 Android 输入栈识别的高精度滚轮单位：30 单位 = 1 格（实测值，不同 ROM 可能不同）。
 * 下发 REL_WHEEL_HI_RES 前按该比例从协议单位换算，保证每格滚动量与协议一致。 */
#define GKME_SYSTEM_SCROLL_UNITS_PER_DETENT 30

static int gkme_hid_to_linux(int usage) {
    for (size_t i = 0; i < sizeof(g_hid_keymap) / sizeof(g_hid_keymap[0]); i++) {
        if (g_hid_keymap[i].usage == (unsigned short)usage) return g_hid_keymap[i].code;
    }
    return 0;
}

static void gkme_write_all(int fd, const struct input_event *events, int count) {
    size_t total = (size_t)count * sizeof(struct input_event);
    const char *p = (const char *)events;
    size_t written = 0;
    while (written < total) {
        ssize_t w = write(fd, p + written, total - written);
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

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeCreateKeyboard(JNIEnv *env, jclass clazz) {
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;

    if (ioctl(fd, GKME_UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, GKME_UI_SET_EVBIT, GKME_EV_REP) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    /* 注册标准键盘键码（1..127）与 F13..F24（183..194）；未用到的位无副作用。 */
    for (int c = 1; c <= 127; c++) {
        if (ioctl(fd, GKME_UI_SET_KEYBIT, c) < 0) {
            int e = errno;
            close(fd);
            return -e;
        }
    }
    for (int c = 183; c <= 194; c++) {
        if (ioctl(fd, GKME_UI_SET_KEYBIT, c) < 0) {
            int e = errno;
            close(fd);
            return -e;
        }
    }

    struct gkme_uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = 0x0006; /* BUS_VIRTUAL */
    setup.id.vendor = 0x045E;  /* Microsoft */
    setup.id.product = 0x00B0; /* 虚拟键盘 */
    setup.id.version = 0x0001;
    snprintf(setup.name, sizeof(setup.name), "%s", "GKME Remote Keyboard");
    setup.ff_effects_max = 0;

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

    gkme_kbd *dev = (gkme_kbd *)calloc(1, sizeof(gkme_kbd));
    if (!dev) {
        ioctl(fd, GKME_UI_DEV_DESTROY);
        close(fd);
        return -ENOMEM;
    }
    dev->fd = fd;
    gkme_store_kbd(fd, dev);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeWriteKeyboard(
        JNIEnv *env, jclass clazz, jint fd, jint modifiers, jintArray usages) {
    gkme_kbd *dev = gkme_get_kbd(fd);
    if (!dev || dev->fd < 0) return;

    struct input_event events[8 + 256 + 1];
    memset(events, 0, sizeof(events));
    int n = 0;

    /* 修饰键：仅在位发生变化时写事件。 */
    unsigned char newmods = (unsigned char)(modifiers & 0xFF);
    for (int i = 0; i < 8; i++) {
        int bit = 1 << i;
        if (((dev->mods ^ newmods) & bit) == 0) continue;
        events[n].type = EV_KEY;
        events[n].code = (uint16_t)g_hid_modmap[i];
        events[n].value = (newmods & bit) ? 1 : 0;
        n++;
    }
    dev->mods = newmods;

    /* 普通键位：把新状态建表，与上一帧逐位比较，仅在变化时写按下/抬起。 */
    unsigned char newmap[256];
    memset(newmap, 0, sizeof(newmap));
    if (usages) {
        jsize len = (*env)->GetArrayLength(env, usages);
        if (len > 256) len = 256;
        if (len > 0) {
            jint buf[256];
            (*env)->GetIntArrayRegion(env, usages, 0, len, buf);
            for (jsize i = 0; i < len; i++) {
                int u = buf[i] & 0xFF;
                if (u >= 0xE0 && u <= 0xE7) continue; /* 修饰键走掩码 */
                newmap[u] = 1;
            }
        }
    }
    for (int u = 0; u < 256; u++) {
        if (newmap[u] == dev->keys[u]) continue;
        int code = gkme_hid_to_linux(u);
        dev->keys[u] = newmap[u];
        if (code <= 0) continue;
        events[n].type = EV_KEY;
        events[n].code = (uint16_t)code;
        events[n].value = newmap[u] ? 1 : 0;
        n++;
    }

    events[n].type = EV_SYN;
    events[n].code = SYN_REPORT;
    events[n].value = 0;
    n++;

    gkme_write_all(dev->fd, events, n);
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeCreateMouse(JNIEnv *env, jclass clazz) {
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;

    if (ioctl(fd, GKME_UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, GKME_UI_SET_EVBIT, EV_REL) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    for (int i = 0; i < 5; i++) {
        if (ioctl(fd, GKME_UI_SET_KEYBIT, g_mouse_btnmap[i]) < 0) {
            int e = errno;
            close(fd);
            return -e;
        }
    }
    const int rels[6] = {GKME_REL_X, GKME_REL_Y, GKME_REL_WHEEL, GKME_REL_HWHEEL,
                         GKME_REL_WHEEL_HI_RES, GKME_REL_HWHEEL_HI_RES};
    for (int i = 0; i < 6; i++) {
        if (ioctl(fd, GKME_UI_SET_RELBIT, rels[i]) < 0) {
            int e = errno;
            close(fd);
            return -e;
        }
    }

    struct gkme_uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = 0x0006; /* BUS_VIRTUAL */
    setup.id.vendor = 0x045E;  /* Microsoft */
    setup.id.product = 0x00B1; /* 虚拟鼠标 */
    setup.id.version = 0x0001;
    snprintf(setup.name, sizeof(setup.name), "%s", "GKME Remote Mouse");
    setup.ff_effects_max = 0;

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

    gkme_ms *dev = (gkme_ms *)calloc(1, sizeof(gkme_ms));
    if (!dev) {
        ioctl(fd, GKME_UI_DEV_DESTROY);
        close(fd);
        return -ENOMEM;
    }
    dev->fd = fd;
    gkme_store_ms(fd, dev);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeWriteMouse(
        JNIEnv *env, jclass clazz, jint fd, jint dx, jint dy, jint wheel, jint pan,
        jint buttons) {
    gkme_ms *dev = gkme_get_ms(fd);
    if (!dev || dev->fd < 0) return;

    struct input_event events[5 + 6 + 1];
    memset(events, 0, sizeof(events));
    int n = 0;

    for (int i = 0; i < 5; i++) {
        int bit = 1 << i;
        if (((dev->buttons ^ buttons) & bit) == 0) continue;
        events[n].type = EV_KEY;
        events[n].code = (uint16_t)g_mouse_btnmap[i];
        events[n].value = (buttons & bit) ? 1 : 0;
        n++;
    }
    dev->buttons = buttons;

    if (dx != 0) {
        events[n].type = EV_REL; events[n].code = GKME_REL_X;
        events[n].value = gkme_clamp_s16(dx); n++;
    }
    if (dy != 0) {
        events[n].type = EV_REL; events[n].code = GKME_REL_Y;
        events[n].value = gkme_clamp_s16(dy); n++;
    }
    if (wheel != 0) {
        /* 高精度：把协议单位换算成本机识别单位（GKME_SYSTEM_SCROLL_UNITS_PER_DETENT = 1 格），
         * 保留亚格精度；用余量累加避免小位移被整除截断。Android 的 CursorScrollAccumulator
         * 在设备声明了 HI_RES 能力时只认这一路。 */
        dev->wheel_hi_rem += wheel * GKME_SYSTEM_SCROLL_UNITS_PER_DETENT;
        int hi = dev->wheel_hi_rem / GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        dev->wheel_hi_rem -= hi * GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        if (hi != 0) {
            events[n].type = EV_REL; events[n].code = GKME_REL_WHEEL_HI_RES;
            events[n].value = hi; n++;
        }
        /* 兼容不支持高精度的输入栈：REL_WHEEL 以整数格计，按协议单位累积、余量留到下一帧。 */
        int acc = dev->wheel_rem + wheel;
        int detents = acc / GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        dev->wheel_rem = acc - detents * GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        if (detents != 0) {
            events[n].type = EV_REL; events[n].code = GKME_REL_WHEEL;
            events[n].value = detents; n++;
        }
    }
    if (pan != 0) {
        dev->pan_hi_rem += pan * GKME_SYSTEM_SCROLL_UNITS_PER_DETENT;
        int hi = dev->pan_hi_rem / GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        dev->pan_hi_rem -= hi * GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        if (hi != 0) {
            events[n].type = EV_REL; events[n].code = GKME_REL_HWHEEL_HI_RES;
            events[n].value = hi; n++;
        }
        int acc = dev->pan_rem + pan;
        int detents = acc / GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        dev->pan_rem = acc - detents * GKME_PROTOCOL_SCROLL_UNITS_PER_DETENT;
        if (detents != 0) {
            events[n].type = EV_REL; events[n].code = GKME_REL_HWHEEL;
            events[n].value = detents; n++;
        }
    }

    events[n].type = EV_SYN;
    events[n].code = SYN_REPORT;
    events[n].value = 0;
    n++;

    gkme_write_all(dev->fd, events, n);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeDestroyInput(JNIEnv *env, jclass clazz,
                                                                     jint fd) {
    gkme_kbd *kbd = gkme_get_kbd(fd);
    if (kbd) {
        gkme_store_kbd(fd, NULL);
        free(kbd);
    }
    gkme_ms *ms = gkme_get_ms(fd);
    if (ms) {
        gkme_store_ms(fd, NULL);
        free(ms);
    }
    if (fd >= 0) {
        ioctl(fd, GKME_UI_DEV_DESTROY);
        close(fd);
    }
}
