/*
 * GKME uhid 虚拟输入后端 —— 直接操作 Linux /dev/uhid。
 *
 * 与 uinput_gamepad.c 并行存在：uinput 后端用 BUS_VIRTUAL 伪装成 Xbox One S，
 * 本文件则用真实的 HID report descriptor（取自 GKMD）经 uhid 创建 DS4 /
 * DualSense / Switch Pro 的 HID 身份，并实现各厂商内核驱动所需的 feature
 * 应答与 output（震动）解析。键盘/鼠标同样以真实 HID 报告描述符呈现。
 *
 * 运行在 Shizuku UserService（shell/root 身份）中，因此可以打开 /dev/uhid。
 */

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>

#include "gkme_hid_descriptors.h"

/* ── uhid 协议常量（避免依赖 NDK 头文件版本）───────────────────────────── */
#define GKME_UHID_DESTROY 1
#define GKME_UHID_START 2
#define GKME_UHID_STOP 3
#define GKME_UHID_OPEN 4
#define GKME_UHID_CLOSE 5
#define GKME_UHID_OUTPUT 6
#define GKME_UHID_GET_REPORT 9
#define GKME_UHID_GET_REPORT_REPLY 10
#define GKME_UHID_CREATE2 11
#define GKME_UHID_INPUT2 12
#define GKME_UHID_SET_REPORT 13
#define GKME_UHID_SET_REPORT_REPLY 14

#define GKME_UHID_DATA_MAX 4096

struct gkme_uhid_create2_req {
    uint8_t name[128];
    uint8_t phys[64];
    uint8_t uniq[64];
    uint16_t rd_size;
    uint16_t bus;
    uint32_t vendor;
    uint32_t product;
    uint32_t version;
    uint32_t country;
    uint8_t rd_data[GKME_UHID_DATA_MAX];
} __attribute__((__packed__));

struct gkme_uhid_input2_req {
    uint16_t size;
    uint8_t data[GKME_UHID_DATA_MAX];
} __attribute__((__packed__));

struct gkme_uhid_output_req {
    uint8_t data[GKME_UHID_DATA_MAX];
    uint16_t size;
    uint8_t rtype;
} __attribute__((__packed__));

struct gkme_uhid_get_report_req {
    uint32_t id;
    uint8_t rnum;
    uint8_t rtype;
} __attribute__((__packed__));

struct gkme_uhid_get_report_reply_req {
    uint32_t id;
    uint16_t err;
    uint16_t size;
    uint8_t data[GKME_UHID_DATA_MAX];
} __attribute__((__packed__));

struct gkme_uhid_set_report_req {
    uint32_t id;
    uint8_t rnum;
    uint8_t rtype;
    uint16_t size;
    uint8_t data[GKME_UHID_DATA_MAX];
} __attribute__((__packed__));

struct gkme_uhid_set_report_reply_req {
    uint32_t id;
    uint16_t err;
} __attribute__((__packed__));

struct gkme_uhid_event {
    uint32_t type;
    union {
        struct gkme_uhid_create2_req create2;
        struct gkme_uhid_input2_req input2;
        struct gkme_uhid_output_req output;
        struct gkme_uhid_get_report_req get_report;
        struct gkme_uhid_get_report_reply_req get_report_reply;
        struct gkme_uhid_set_report_req set_report;
        struct gkme_uhid_set_report_reply_req set_report_reply;
        uint8_t data[GKME_UHID_DATA_MAX];
    } u;
} __attribute__((__packed__));

/* ── 设备种类 ─────────────────────────────────────────────────────────── */
#define GKME_PROFILE_DS4 1
#define GKME_PROFILE_DUALSENSE 2
#define GKME_PROFILE_SWITCH_PRO 3
#define GKME_PROFILE_KEYBOARD 10
#define GKME_PROFILE_MOUSE 11

#define GKME_MAX_DEV 256

typedef struct {
    int fd;
    int profile;
    volatile int running;
    pthread_t reader;
    pthread_t streamer;
    int has_streamer;
    pthread_mutex_t lock;

    /* 手柄震动（0..65535，分别对应左右马达） */
    int left;
    int right;

    /* 本机模式为 0：忽略 output 报告里的震动，避免“手机震动 ↔ 虚拟手柄”回环。 */
    int rumble_enabled;

    /* 运动传感器（rad/s、m/s²），由 App 每帧注入，写入 uhid 手柄的 input 报告。 */
    float gyro[3];
    float accel[3];

    /* 触摸板（DS4/DualSense）：2 个触点，每点 [id, x, y, active]，x/y 为 0..1919/0..942。 */
    int touch[2][4];
    uint8_t touch_seq;

    /* Switch 状态 */
    uint8_t sw_body[48];
    int sw_imu;
    uint8_t sw_timer;
    uint8_t sw_device_type;

    /* 最近一帧输入报告（Switch 需要周期性重发） */
    uint8_t last_report[64];
    int last_len;

    int index;
} gkme_uk_dev;

static gkme_uk_dev *g_uk_devs[GKME_MAX_DEV];
static pthread_mutex_t g_uk_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_uk_index = 0;

static void gkme_uk_store(int fd, gkme_uk_dev *dev) {
    if (fd < 0 || fd >= GKME_MAX_DEV) return;
    pthread_mutex_lock(&g_uk_lock);
    g_uk_devs[fd] = dev;
    pthread_mutex_unlock(&g_uk_lock);
}

static gkme_uk_dev *gkme_uk_get(int fd) {
    if (fd < 0 || fd >= GKME_MAX_DEV) return NULL;
    pthread_mutex_lock(&g_uk_lock);
    gkme_uk_dev *dev = g_uk_devs[fd];
    pthread_mutex_unlock(&g_uk_lock);
    return dev;
}

/* ── 通用事件发送 ─────────────────────────────────────────────────────── */
static int gkme_uk_send_input(int fd, const uint8_t *data, int len) {
    struct gkme_uhid_event ev;
    if (len > GKME_UHID_DATA_MAX) len = GKME_UHID_DATA_MAX;
    memset(&ev, 0, sizeof(ev));
    ev.type = GKME_UHID_INPUT2;
    ev.u.input2.size = (uint16_t)len;
    if (len > 0) memcpy(ev.u.input2.data, data, len);
    return write(fd, &ev, sizeof(ev)) < 0 ? -errno : 0;
}

/* ── Sony 通用数据（取自 GKMD）────────────────────────────────────────── */
static const uint8_t kSonyCalibration[34] = {
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x10, 0x27, 0xF0, 0xD8, 0x10, 0x27, 0xF0, 0xD8, 0x10, 0x27, 0xF0, 0xD8,
    0xF4, 0x01, 0xF4, 0x01,
    0x10, 0x27, 0xF0, 0xD8, 0x10, 0x27, 0xF0, 0xD8, 0x10, 0x27, 0xF0, 0xD8,
};

static const uint8_t kDs4Feature20[64] = {
    0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x4A, 0x00, 0x44, 0x00, 0x4D, 0x00, 0x2D, 0x00,
    0x30, 0x00, 0x35, 0x00, 0x30, 0x00, 0x00, 0x00,
};

static const uint8_t kDs4Feature81[64] = {
    0x81, 0x03, 0x03, 0x03, 0x01,
};

static const uint8_t kDs4Firmware[49] = {
    0xA3, 0x41, 0x75, 0x67, 0x20, 0x20, 0x33, 0x20,
    0x32, 0x30, 0x31, 0x33, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x30, 0x37, 0x3A, 0x30, 0x31, 0x3A, 0x31,
    0x32, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x01, 0x00, 0xA4, 0x03, 0x00, 0x00,
    0x00, 0x49, 0x00, 0x05, 0x00, 0x00, 0x80, 0x03,
    0x00
};

static const uint8_t kDs5Firmware[64] = {
    0x20, 0x4A, 0x75, 0x6C, 0x20, 0x20, 0x34, 0x20,
    0x32, 0x30, 0x32, 0x35, 0x31, 0x30, 0x3A, 0x33,
    0x38, 0x00, 0x00, 0x00, 0x02, 0x00, 0x0B, 0x00,
    0x07, 0x11, 0x00, 0x00, 0x2A, 0x00, 0x10, 0x01,
    0x01, 0xC8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x30, 0x06, 0x00, 0x00,
    0x3C, 0x00, 0x01, 0x00, 0x0A, 0x00, 0x02, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};

static const uint8_t kSwitchMac[6] = { 0x98, 0xB6, 0xE9, 0x48, 0x4D, 0x30 };

/* ── 输入辅助 ─────────────────────────────────────────────────────────── */
static int gkme_axis_u8(int v) {
    if (v < -32768) v = -32768;
    if (v > 32767) v = 32767;
    int out = (v + 32768) >> 8; /* 0..255 */
    if (out < 0) out = 0;
    if (out > 255) out = 255;
    return out;
}

static int gkme_trig_u8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}

/* XInput 十字键（bit0=上 1=下 2=左 3=右）→ 8 向 hat（0=上，顺时针；8=中） */
static int gkme_hat_octant(int buttons) {
    int up = (buttons >> 0) & 1;
    int down = (buttons >> 1) & 1;
    int left = (buttons >> 2) & 1;
    int right = (buttons >> 3) & 1;
    if (!up && !down && !left && !right) return 8;
    if (up && right) return 1;
    if (right && down) return 3;
    if (down && left) return 5;
    if (left && up) return 7;
    if (up) return 0;
    if (right) return 2;
    if (down) return 4;
    if (left) return 6;
    return 8;
}

/* ── 运动传感器换算与写入 ─────────────────────────────────────────────── */
static void gkme_put_s16(uint8_t *p, int v) {
    if (v < -32768) v = -32768;
    if (v > 32767) v = 32767;
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
}

/* rad/s → DS4/DualSense 陀螺仪原始刻度（16 LSB/(°/s)）。 */
static int gkme_gyro_raw(float rad_s) {
    return (int)(rad_s * 57.2957795f * 16.0f);
}

/* m/s² → DS4/DualSense 加速度原始刻度（8192 LSB/g）。 */
static int gkme_accel_raw(float m_s2) {
    return (int)(m_s2 / 9.80665f * 8192.0f);
}

/* Switch IMU 刻度：加速度约 4096 LSB/g，陀螺仪约 938 LSB/(rad/s)。 */
static int gkme_switch_accel_raw(float m_s2) {
    return (int)(m_s2 / 9.80665f * 4096.0f);
}

static int gkme_switch_gyro_raw(float rad_s) {
    return (int)(rad_s * 938.0f);
}

/* 写入 2 个触摸点到 DS4/DualSense input 报告（每点 4 字节 + 1 字节序号）。
 * y 按 yscale_num/yscale_den 缩放：DS4 触板 1920x943，DualSense 1920x1080。
 * 每点编码：byte0 = bit7 未触摸标志 + 7 位 id；byte1 = X 低 8 位；
 * byte2 = X 高 4 位 | Y 高 4 位；byte3 = Y 低 8 位。 */
static void gkme_write_touch(uint8_t *rpt, int offset, gkme_uk_dev *dev,
                             int yscale_num, int yscale_den) {
    for (int i = 0; i < 2; i++) {
        int id = dev->touch[i][0] & 0x7F;
        int x = dev->touch[i][1] & 0x0FFF;
        int y = (dev->touch[i][2] * yscale_num) / yscale_den;
        if (y < 0) y = 0;
        if (y > 0x0FFF) y = 0x0FFF;
        int active = dev->touch[i][3];
        uint8_t *p = rpt + offset + i * 4;
        p[0] = active ? (uint8_t)id : (uint8_t)(0x80 | id);
        p[1] = (uint8_t)(x & 0xFF);
        p[2] = (uint8_t)((((x >> 8) & 0x0F) << 4) | ((y >> 8) & 0x0F));
        p[3] = (uint8_t)(y & 0xFF);
    }
    rpt[offset + 8] = dev->touch_seq++;
}

static void gkme_pack_ds4(uint8_t rpt[64], gkme_uk_dev *dev,
                          int buttons, int lt, int rt,
                          int lx, int ly, int rx, int ry) {
    memset(rpt, 0, 64);
    rpt[0] = 0x01;
    rpt[1] = (uint8_t)gkme_axis_u8(lx);
    rpt[2] = (uint8_t)gkme_axis_u8(ly);
    rpt[3] = (uint8_t)gkme_axis_u8(rx);
    rpt[4] = (uint8_t)gkme_axis_u8(ry);
    uint8_t face = 0;
    if (buttons & 0x4000) face |= 0x10; /* X (Square) */
    if (buttons & 0x1000) face |= 0x20; /* A (Cross) */
    if (buttons & 0x2000) face |= 0x40; /* B (Circle) */
    if (buttons & 0x8000) face |= 0x80; /* Y (Triangle) */
    rpt[5] = (uint8_t)(gkme_hat_octant(buttons) | face);
    uint8_t b2 = 0;
    if (buttons & 0x0100) b2 |= 0x01; /* LB */
    if (buttons & 0x0200) b2 |= 0x02; /* RB */
    if (lt > 0) b2 |= 0x04;
    if (rt > 0) b2 |= 0x08;
    if (buttons & 0x0020) b2 |= 0x10; /* Back */
    if (buttons & 0x0010) b2 |= 0x20; /* Start */
    if (buttons & 0x0040) b2 |= 0x40; /* L3 */
    if (buttons & 0x0080) b2 |= 0x80; /* R3 */
    rpt[6] = b2;
    if (buttons & 0x0400) rpt[7] |= 0x01; /* Guide */
    if (buttons & 0x20000) rpt[7] |= 0x02; /* 触摸板点击 */
    rpt[8] = (uint8_t)gkme_trig_u8(lt);
    rpt[9] = (uint8_t)gkme_trig_u8(rt);
    /* 运动传感器：gyro X/Y/Z 在 13..18，accel X/Y/Z 在 19..24（hid-playstation）。 */
    gkme_put_s16(rpt + 13, gkme_gyro_raw(dev->gyro[0]));
    gkme_put_s16(rpt + 15, gkme_gyro_raw(dev->gyro[1]));
    gkme_put_s16(rpt + 17, gkme_gyro_raw(dev->gyro[2]));
    gkme_put_s16(rpt + 19, gkme_accel_raw(dev->accel[0]));
    gkme_put_s16(rpt + 21, gkme_accel_raw(dev->accel[1]));
    gkme_put_s16(rpt + 23, gkme_accel_raw(dev->accel[2]));
    rpt[30] = 0x05; /* battery: full-ish */
    /* 触摸板：2 个触点，DS4 触板 1920x943。 */
    gkme_write_touch(rpt, 33, dev, 1, 1);
}

static void gkme_pack_dualsense(uint8_t rpt[64], gkme_uk_dev *dev,
                                int buttons, int lt, int rt,
                                int lx, int ly, int rx, int ry) {
    memset(rpt, 0, 64);
    rpt[0] = 0x01;
    rpt[1] = (uint8_t)gkme_axis_u8(lx);
    rpt[2] = (uint8_t)gkme_axis_u8(ly);
    rpt[3] = (uint8_t)gkme_axis_u8(rx);
    rpt[4] = (uint8_t)gkme_axis_u8(ry);
    rpt[5] = (uint8_t)gkme_trig_u8(lt);
    rpt[6] = (uint8_t)gkme_trig_u8(rt);
    rpt[7] = 0x00; /* sequence */
    uint8_t face = 0;
    if (buttons & 0x4000) face |= 0x10;
    if (buttons & 0x1000) face |= 0x20;
    if (buttons & 0x2000) face |= 0x40;
    if (buttons & 0x8000) face |= 0x80;
    rpt[8] = (uint8_t)(gkme_hat_octant(buttons) | face);
    uint8_t b2 = 0;
    if (buttons & 0x0100) b2 |= 0x01;
    if (buttons & 0x0200) b2 |= 0x02;
    if (lt > 0) b2 |= 0x04;
    if (rt > 0) b2 |= 0x08;
    if (buttons & 0x0020) b2 |= 0x10;
    if (buttons & 0x0010) b2 |= 0x20;
    if (buttons & 0x0040) b2 |= 0x40;
    if (buttons & 0x0080) b2 |= 0x80;
    rpt[9] = b2;
    if (buttons & 0x0400) rpt[10] |= 0x01;
    if (buttons & 0x20000) rpt[10] |= 0x02; /* 触摸板点击 */
    /* 运动传感器：gyro X/Y/Z 在 16..21，accel X/Y/Z 在 22..27（hid-playstation）。 */
    gkme_put_s16(rpt + 16, gkme_gyro_raw(dev->gyro[0]));
    gkme_put_s16(rpt + 18, gkme_gyro_raw(dev->gyro[1]));
    gkme_put_s16(rpt + 20, gkme_gyro_raw(dev->gyro[2]));
    gkme_put_s16(rpt + 22, gkme_accel_raw(dev->accel[0]));
    gkme_put_s16(rpt + 24, gkme_accel_raw(dev->accel[1]));
    gkme_put_s16(rpt + 26, gkme_accel_raw(dev->accel[2]));
    rpt[53] = 0x05;
    /* 触摸板：DualSense 触板 1920x1080，把 0..942 的输入 y 缩放过去。 */
    gkme_write_touch(rpt, 33, dev, 1080, 943);
}

/* Switch 12 位摇杆（little-nibble 打包）。 */
static uint16_t gkme_switch_stick(int v, int invert) {
    if (v < -32768) v = -32768;
    if (v > 32767) v = 32767;
    int centered = v;                 /* -32768..32767 */
    if (invert) centered = -centered;
    int raw = 0x800 + (centered * 0x600) / 32768;
    if (raw < 0) raw = 0;
    if (raw > 0xFFF) raw = 0xFFF;
    return (uint16_t)raw;
}

static void gkme_switch_pack_stick(uint8_t *dst, uint16_t x, uint16_t y) {
    dst[0] = (uint8_t)(x & 0xFF);
    dst[1] = (uint8_t)(((x >> 8) & 0x0F) | ((y & 0x0F) << 4));
    dst[2] = (uint8_t)(y >> 4);
}

static void gkme_switch_fill_body(gkme_uk_dev *dev, int buttons, int lt, int rt,
                                  int lx, int ly, int rx, int ry) {
    uint8_t *body = dev->sw_body;
    memset(body, 0, sizeof(dev->sw_body));
    uint8_t b0 = 0, b1 = 0, b2 = 0;
    if (buttons & 0x4000) b0 |= 0x01; /* left  (Square) */
    if (buttons & 0x8000) b0 |= 0x02; /* top   (Triangle) */
    if (buttons & 0x1000) b0 |= 0x04; /* bottom(Cross) */
    if (buttons & 0x2000) b0 |= 0x08; /* right (Circle) */
    if (buttons & 0x0200) b0 |= 0x40; /* R */
    if (rt > 0) b0 |= 0x80;           /* ZR */
    if (buttons & 0x0020) b1 |= 0x01; /* Minus */
    if (buttons & 0x0010) b1 |= 0x02; /* Plus */
    if (buttons & 0x0080) b1 |= 0x04; /* RStick */
    if (buttons & 0x0040) b1 |= 0x08; /* LStick */
    if (buttons & 0x0400) b1 |= 0x10; /* Home */
    if (buttons & 0x0002) b2 |= 0x01; /* down */
    if (buttons & 0x0001) b2 |= 0x02; /* up */
    if (buttons & 0x0008) b2 |= 0x04; /* right */
    if (buttons & 0x0004) b2 |= 0x08; /* left */
    if (buttons & 0x0100) b2 |= 0x40; /* L */
    if (lt > 0) b2 |= 0x80;           /* ZL */
    body[2] = b0;
    body[3] = b1;
    body[4] = b2;
    gkme_switch_pack_stick(body + 5, gkme_switch_stick(lx, 0), gkme_switch_stick(ly, 1));
    gkme_switch_pack_stick(body + 8, gkme_switch_stick(rx, 0), gkme_switch_stick(ry, 1));
    /* IMU：3 组 sample，每组 accel X/Y/Z + gyro X/Y/Z（int16 LE），位于 body[12..47]。 */
    for (int s = 0; s < 3; s++) {
        uint8_t *imu = body + 12 + s * 12;
        gkme_put_s16(imu + 0, gkme_switch_accel_raw(dev->accel[0]));
        gkme_put_s16(imu + 2, gkme_switch_accel_raw(dev->accel[1]));
        gkme_put_s16(imu + 4, gkme_switch_accel_raw(dev->accel[2]));
        gkme_put_s16(imu + 6, gkme_switch_gyro_raw(dev->gyro[0]));
        gkme_put_s16(imu + 8, gkme_switch_gyro_raw(dev->gyro[1]));
        gkme_put_s16(imu + 10, gkme_switch_gyro_raw(dev->gyro[2]));
    }
}

static void gkme_switch_fill_state(gkme_uk_dev *dev, uint8_t state[46]) {
    memset(state, 0, 46);
    memcpy(state, dev->sw_body + 2, 9);
    if (dev->sw_imu) memcpy(state + 10, dev->sw_body + 12, 36);
}

static void gkme_switch_build_frame(gkme_uk_dev *dev, uint8_t frame[64]) {
    uint8_t state[46];
    gkme_switch_fill_state(dev, state);
    memset(frame, 0, 64);
    frame[0] = 0x30;
    frame[1] = dev->sw_timer++;
    frame[2] = 0x91; /* full + wired */
    memcpy(frame + 3, state, 9);
    frame[12] = 0xB0;
    memcpy(frame + 13, state + 10, 36);
}

/* ── Switch SPI 镜像（取自 GKMD）──────────────────────────────────────── */
static uint8_t gkme_switch_spi_byte(gkme_uk_dev *dev, uint32_t a) {
    static const uint8_t imuCal[24] = {
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x40, 0x00, 0x40, 0x00, 0x40,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x3B, 0x34, 0x3B, 0x34, 0x3B, 0x34,
    };
    static const uint8_t stickCal[18] = {
        0x00, 0x06, 0x60, 0x00, 0x08, 0x80, 0x00, 0x06, 0x60,
        0x00, 0x08, 0x80, 0x00, 0x06, 0x60, 0x00, 0x06, 0x60,
    };
    static const uint8_t colors[12] = {
        0x32, 0x32, 0x32, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    };
    static const uint8_t sixAxis[6] = { 0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F };
    static const uint8_t stickParams[18] = {
        0x0F, 0x30, 0x61, 0x00, 0x30, 0xF3, 0xD4, 0x14, 0x54,
        0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63,
    };
    static const uint8_t serialNo[16] = {
        0x00, 0x58, 0x41, 0x57, 0x31, 0x30, 0x30, 0x30,
        0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x00,
    };
    if (a >= 0x6000 && a < 0x6010) return serialNo[a - 0x6000];
    if (a == 0x6012) return dev->sw_device_type;
    if (a >= 0x6020 && a < 0x6038) return imuCal[a - 0x6020];
    if (a >= 0x603D && a < 0x604F) return stickCal[a - 0x603D];
    if (a >= 0x6050 && a < 0x605C) return colors[a - 0x6050];
    if (a >= 0x6080 && a < 0x6086) return sixAxis[a - 0x6080];
    if (a >= 0x6086 && a < 0x6098) return stickParams[a - 0x6086];
    if (a >= 0x6098 && a < 0x60AA) return stickParams[a - 0x6098];
    return 0xFF;
}

static uint8_t gkme_switch_mac(gkme_uk_dev *dev, int i) {
    if (i == 5) return (uint8_t)(kSwitchMac[5] + dev->index);
    return kSwitchMac[i];
}

/* ── Feature 应答 ─────────────────────────────────────────────────────── */
static int gkme_build_feature(gkme_uk_dev *dev, uint8_t rnum, uint8_t *out, int cap) {
    int n = 0;
    if (dev->profile == GKME_PROFILE_DS4) {
        switch (rnum) {
        case 0x02:
            if (cap < 37) return 0;
            out[0] = 0x02;
            memcpy(out + 1, kSonyCalibration, sizeof(kSonyCalibration));
            return 37;
        case 0x81:
            n = (int)sizeof(kDs4Feature81);
            if (n > cap) n = cap;
            memcpy(out, kDs4Feature81, n);
            return n;
        case 0x20:
            n = (int)sizeof(kDs4Feature20);
            if (n > cap) n = cap;
            memcpy(out, kDs4Feature20, n);
            return n;
        case 0x12:
            if (cap < 16) return 0;
            memset(out, 0, 16);
            out[0] = 0x12;
            out[1] = 0x02; out[2] = 0x48; out[3] = 0x4D;
            out[4] = 0x00; out[5] = 0x00; out[6] = (uint8_t)dev->index;
            return 16;
        case 0xA3:
            n = (int)sizeof(kDs4Firmware);
            if (n > cap) n = cap;
            memcpy(out, kDs4Firmware, n);
            return n;
        default:
            return 0;
        }
    }
    if (dev->profile == GKME_PROFILE_DUALSENSE) {
        switch (rnum) {
        case 0x05:
            if (cap < 41) return 0;
            memset(out, 0, 41);
            out[0] = 0x05;
            memcpy(out + 1, kSonyCalibration, sizeof(kSonyCalibration));
            return 41;
        case 0x09:
            if (cap < 20) return 0;
            memset(out, 0, 20);
            out[0] = 0x09;
            out[1] = 0x02; out[2] = 0x48; out[3] = 0x4D;
            out[4] = 0x00; out[5] = 0x00; out[6] = (uint8_t)dev->index;
            return 20;
        case 0x20:
            n = (int)sizeof(kDs5Firmware);
            if (n > cap) n = cap;
            memcpy(out, kDs5Firmware, n);
            return n;
        case 0x22:
            if (cap < 64) return 0;
            memset(out, 0, 64);
            out[0] = 0x22;
            return 64;
        default:
            return 0;
        }
    }
    if (dev->profile == GKME_PROFILE_KEYBOARD || dev->profile == GKME_PROFILE_MOUSE) {
        /* 鼠标的 Resolution Multiplier（无 report id）需返回有效值，否则内核写
         * feature 时可能阻塞。键盘无 feature，返回 0 即可。 */
        if (rnum == 0 && cap >= 1) {
            out[0] = 0x01;
            return 1;
        }
        return 0;
    }
    return 0;
}

/* ── Output（震动）解析 ───────────────────────────────────────────────── */
static void gkme_set_rumble(gkme_uk_dev *dev, int left, int right) {
    if (left < 0) left = 0; if (left > 255) left = 255;
    if (right < 0) right = 0; if (right > 255) right = 255;
    pthread_mutex_lock(&dev->lock);
    dev->left = left * 257;  /* → 0..65535 */
    dev->right = right * 257;
    pthread_mutex_unlock(&dev->lock);
}

static void gkme_handle_output(gkme_uk_dev *dev, const uint8_t *data, int len) {
    /* 本机模式禁用震动回传：忽略 output 报告，避免“手机震动 ↔ 虚拟手柄”死循环。 */
    if (!dev->rumble_enabled) return;
    if (len <= 0) return;
    uint8_t rid = data[0];
    if (dev->profile == GKME_PROFILE_DS4) {
        if (rid == 0x05 && len >= 6) gkme_set_rumble(dev, data[5], data[4]);
    } else if (dev->profile == GKME_PROFILE_DUALSENSE) {
        if (rid == 0x02 && len >= 5) gkme_set_rumble(dev, data[4], data[3]);
    } else if (dev->profile == GKME_PROFILE_SWITCH_PRO) {
        /* 0x01 携带 rumble+subcommand，0x10 仅 rumble；每侧 4 字节 HD rumble 块。 */
        if ((rid == 0x01 || rid == 0x10) && len >= 9) {
            int lAmp = data[2] > data[4] ? data[2] : data[4];
            int rAmp = data[6] > data[8] ? data[6] : data[8];
            gkme_set_rumble(dev, lAmp, rAmp);
        }
    }
}

/* ── Switch 协议处理 ──────────────────────────────────────────────────── */
static void gkme_switch_proprietary(gkme_uk_dev *dev, const uint8_t *payload, int len) {
    if (len < 1) return;
    uint8_t cmd = payload[0];
    uint8_t reply[64];
    memset(reply, 0, sizeof(reply));
    reply[0] = 0x81;
    reply[1] = cmd;
    if (cmd == 0x01) {
        reply[2] = 0x00;
        reply[3] = dev->sw_device_type;
        for (int i = 0; i < 6; i++) reply[4 + i] = gkme_switch_mac(dev, 5 - i);
        gkme_uk_send_input(dev->fd, reply, 64);
    } else if (cmd == 0x02 || cmd == 0x03) {
        gkme_uk_send_input(dev->fd, reply, 64);
    }
}

static void gkme_switch_subcommand(gkme_uk_dev *dev, const uint8_t *data, int len) {
    /* data[0]=report id(0x01) [1]=counter [2..9]=rumble [10]=subcmd [11..]=args */
    if (len < 11) return;
    uint8_t subcmd = data[10];
    const uint8_t *args = data + 11;
    int arglen = len - 11;

    uint8_t state[46];
    gkme_switch_fill_state(dev, state);

    uint8_t reply[64];
    memset(reply, 0, sizeof(reply));
    reply[0] = 0x21;
    reply[1] = dev->sw_timer++;
    reply[2] = 0x91;
    memcpy(reply + 3, state, 9);
    reply[12] = 0xB0;
    reply[13] = 0x80;
    reply[14] = subcmd;

    switch (subcmd) {
    case 0x02:
        reply[13] = 0x82;
        reply[15] = 0x03;
        reply[16] = 0x8B;
        reply[17] = dev->sw_device_type;
        reply[18] = 0x02;
        for (int i = 0; i < 6; i++) reply[19 + i] = gkme_switch_mac(dev, i);
        reply[25] = 0x01;
        reply[26] = 0x01;
        break;
    case 0x03:
        break;
    case 0x04:
        reply[13] = 0x83;
        break;
    case 0x10: {
        if (arglen < 5) break;
        uint32_t addr = (uint32_t)(args[0] | (args[1] << 8) | (args[2] << 16) | (args[3] << 24));
        int n = args[4];
        if (n > 0x1D) n = 0x1D;
        reply[13] = 0x90;
        memcpy(reply + 15, args, 5);
        for (int i = 0; i < n; i++) reply[20 + i] = gkme_switch_spi_byte(dev, addr + (uint32_t)i);
        break;
    }
    case 0x21:
        reply[13] = 0xA0;
        reply[15] = 0x01; reply[16] = 0x00; reply[17] = 0xFF;
        reply[18] = 0x00; reply[19] = 0x08; reply[20] = 0x00;
        reply[21] = 0x1B; reply[22] = 0x01;
        reply[48] = 0xC8;
        break;
    case 0x40:
        if (arglen >= 1) dev->sw_imu = args[0] != 0;
        break;
    case 0x48:
        reply[13] = 0x82;
        break;
    default:
        break;
    }
    gkme_uk_send_input(dev->fd, reply, 64);
}

/* ── 读事件线程 ───────────────────────────────────────────────────────── */
static void *gkme_uk_reader(void *arg) {
    gkme_uk_dev *dev = (gkme_uk_dev *)arg;
    while (dev->running) {
        struct pollfd pfd;
        pfd.fd = dev->fd;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 200);
        if (pr <= 0) continue;
        struct gkme_uhid_event ev;
        ssize_t n = read(dev->fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ev.type == GKME_UHID_STOP) {
            dev->running = 0;
            break;
        }
        if (ev.type == GKME_UHID_GET_REPORT) {
            uint8_t buf[64];
            int len = gkme_build_feature(dev, ev.u.get_report.rnum, buf, (int)sizeof(buf));
            struct gkme_uhid_event out;
            memset(&out, 0, sizeof(out));
            out.type = GKME_UHID_GET_REPORT_REPLY;
            out.u.get_report_reply.id = ev.u.get_report.id;
            out.u.get_report_reply.err = 0;
            out.u.get_report_reply.size = (uint16_t)len;
            if (len > 0) memcpy(out.u.get_report_reply.data, buf, len);
            write(dev->fd, &out, sizeof(out));
        } else if (ev.type == GKME_UHID_SET_REPORT) {
            struct gkme_uhid_event out;
            memset(&out, 0, sizeof(out));
            out.type = GKME_UHID_SET_REPORT_REPLY;
            out.u.set_report_reply.id = ev.u.set_report.id;
            out.u.set_report_reply.err = 0;
            write(dev->fd, &out, sizeof(out));
        } else if (ev.type == GKME_UHID_OUTPUT) {
            int olen = ev.u.output.size;
            if (olen > GKME_UHID_DATA_MAX) olen = GKME_UHID_DATA_MAX;
            if (dev->profile == GKME_PROFILE_SWITCH_PRO) {
                if (olen >= 1 && ev.u.output.data[0] == 0x80) {
                    gkme_switch_proprietary(dev, ev.u.output.data + 1, olen - 1);
                } else if (olen >= 1 && ev.u.output.data[0] == 0x01) {
                    gkme_switch_subcommand(dev, ev.u.output.data, olen);
                }
            }
            gkme_handle_output(dev, ev.u.output.data, olen);
        }
    }
    return NULL;
}

static void *gkme_uk_streamer(void *arg) {
    gkme_uk_dev *dev = (gkme_uk_dev *)arg;
    while (dev->running) {
        usleep(15000);
        if (!dev->running) break;
        if (!dev->has_streamer) continue;
        pthread_mutex_lock(&dev->lock);
        uint8_t frame[64];
        gkme_switch_build_frame(dev, frame);
        pthread_mutex_unlock(&dev->lock);
        gkme_uk_send_input(dev->fd, frame, 64);
    }
    return NULL;
}

/* ── 创建 / 销毁 ──────────────────────────────────────────────────────── */
static int gkme_uk_create(const uint8_t *desc, int desc_len, const char *name,
                          const char *uniq, uint16_t vid, uint16_t pid,
                          int profile, int rumble_enabled) {
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;

    struct gkme_uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = GKME_UHID_CREATE2;
    snprintf((char *)ev.u.create2.name, sizeof(ev.u.create2.name), "%s", name);
    snprintf((char *)ev.u.create2.uniq, sizeof(ev.u.create2.uniq), "%s", uniq);
    ev.u.create2.rd_size = (uint16_t)desc_len;
    ev.u.create2.bus = 0x03; /* BUS_USB */
    ev.u.create2.vendor = vid;
    ev.u.create2.product = pid;
    ev.u.create2.version = 0x0100;
    memcpy(ev.u.create2.rd_data, desc, desc_len);
    if (write(fd, &ev, sizeof(ev)) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }

    gkme_uk_dev *dev = (gkme_uk_dev *)calloc(1, sizeof(gkme_uk_dev));
    if (!dev) {
        close(fd);
        return -ENOMEM;
    }
    pthread_mutex_init(&dev->lock, NULL);
    dev->fd = fd;
    dev->profile = profile;
    dev->running = 1;
    dev->sw_imu = 0;
    dev->sw_device_type = 3; /* Pro */
    dev->index = g_uk_index++;
    gkme_uk_store(fd, dev);
    pthread_create(&dev->reader, NULL, gkme_uk_reader, dev);
    if (profile == GKME_PROFILE_SWITCH_PRO) {
        dev->has_streamer = 1;
        pthread_create(&dev->streamer, NULL, gkme_uk_streamer, dev);
    }
    dev->rumble_enabled = rumble_enabled;
    return fd;
}

static void gkme_uk_destroy(int fd) {
    gkme_uk_dev *dev = gkme_uk_get(fd);
    if (!dev) return;
    gkme_uk_store(fd, NULL);
    dev->running = 0;
    if (dev->has_streamer) pthread_join(dev->streamer, NULL);
    pthread_join(dev->reader, NULL);
    if (dev->fd >= 0) close(dev->fd);
    pthread_mutex_destroy(&dev->lock);
    free(dev);
}

/* ── JNI：手柄 ────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeCreateUhid(
        JNIEnv *env, jclass clazz, jint profile, jint rumbleEnabled) {
    switch (profile) {
    case GKME_PROFILE_DS4:
        return gkme_uk_create(GKMD_DESC_DS4, GKMD_DESC_DS4_LEN, "Wireless Controller",
                              "JDM-050", 0x054C, 0x09CC, profile, rumbleEnabled);
    case GKME_PROFILE_DUALSENSE:
        return gkme_uk_create(GKMD_DESC_DUALSENSE, GKMD_DESC_DUALSENSE_LEN,
                              "DualSense Wireless Controller", "GKME-DS5",
                              0x054C, 0x0CE6, profile, rumbleEnabled);
    case GKME_PROFILE_SWITCH_PRO:
        return gkme_uk_create(GKMD_DESC_SWITCH_PRO, GKMD_DESC_SWITCH_PRO_LEN,
                              "Pro Controller", "000000000001",
                              0x057E, 0x2009, profile, rumbleEnabled);
    default:
        return -EINVAL;
    }
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeWriteUhid(
        JNIEnv *env, jclass clazz, jint fd, jint buttons, jint leftTrigger,
        jint rightTrigger, jint leftX, jint leftY, jint rightX, jint rightY,
        jfloat gyroX, jfloat gyroY, jfloat gyroZ,
        jfloat accelX, jfloat accelY, jfloat accelZ, jintArray touches) {
    gkme_uk_dev *dev = gkme_uk_get(fd);
    if (!dev) return;
    uint8_t rpt[64];
    int len = 64;
    pthread_mutex_lock(&dev->lock);
    dev->gyro[0] = gyroX; dev->gyro[1] = gyroY; dev->gyro[2] = gyroZ;
    dev->accel[0] = accelX; dev->accel[1] = accelY; dev->accel[2] = accelZ;
    if (touches) {
        jsize n = (*env)->GetArrayLength(env, touches);
        if (n > 0) {
            jint *tv = (*env)->GetIntArrayElements(env, touches, NULL);
            if (tv) {
                for (int i = 0; i < 2; i++) {
                    int base = i * 4;
                    if (base + 3 < (int)n) {
                        dev->touch[i][0] = tv[base];
                        dev->touch[i][1] = tv[base + 1];
                        dev->touch[i][2] = tv[base + 2];
                        dev->touch[i][3] = tv[base + 3];
                    } else {
                        dev->touch[i][3] = 0;
                    }
                }
                (*env)->ReleaseIntArrayElements(env, touches, tv, JNI_ABORT);
            }
        }
    }
    if (dev->profile == GKME_PROFILE_DS4) {
        gkme_pack_ds4(rpt, dev, buttons, leftTrigger, rightTrigger,
                      leftX, leftY, rightX, rightY);
    } else if (dev->profile == GKME_PROFILE_DUALSENSE) {
        gkme_pack_dualsense(rpt, dev, buttons, leftTrigger, rightTrigger,
                            leftX, leftY, rightX, rightY);
    } else if (dev->profile == GKME_PROFILE_SWITCH_PRO) {
        gkme_switch_fill_body(dev, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY);
        gkme_switch_build_frame(dev, rpt);
    } else {
        pthread_mutex_unlock(&dev->lock);
        return;
    }
    if (dev->profile != GKME_PROFILE_SWITCH_PRO) {
        memcpy(dev->last_report, rpt, len);
        dev->last_len = len;
    }
    pthread_mutex_unlock(&dev->lock);
    gkme_uk_send_input(dev->fd, rpt, len);
}

JNIEXPORT jlong JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeRumbleUhid(
        JNIEnv *env, jclass clazz, jint fd) {
    gkme_uk_dev *dev = gkme_uk_get(fd);
    if (!dev) return 0;
    int left, right;
    pthread_mutex_lock(&dev->lock);
    left = dev->left;
    right = dev->right;
    pthread_mutex_unlock(&dev->lock);
    return ((jlong)(left & 0xFFFF) << 16) | (jlong)(right & 0xFFFF);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeDestroyUhid(
        JNIEnv *env, jclass clazz, jint fd) {
    gkme_uk_destroy(fd);
}

/* ── JNI：键盘 ────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeCreateKeyboardUhid(
        JNIEnv *env, jclass clazz) {
    return gkme_uk_create(GKMD_DESC_KEYBOARD, GKMD_DESC_KEYBOARD_LEN, "GKME HID Keyboard",
                          "1337", 0x2E8A, 0x0010, GKME_PROFILE_KEYBOARD, 0);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeWriteKeyboardUhid(
        JNIEnv *env, jclass clazz, jint fd, jint modifiers, jintArray usages) {
    gkme_uk_dev *dev = gkme_uk_get(fd);
    if (!dev) return;
    uint8_t rpt[34];
    memset(rpt, 0, sizeof(rpt));
    rpt[0] = (uint8_t)(modifiers & 0xFF);
    if (usages) {
        jsize len = (*env)->GetArrayLength(env, usages);
        jint buf[256];
        if (len > 256) len = 256;
        if (len > 0) {
            (*env)->GetIntArrayRegion(env, usages, 0, len, buf);
            for (jsize i = 0; i < len; i++) {
                int u = buf[i] & 0xFF;
                if (u >= 0xE0 && u <= 0xE7) continue; /* 修饰键走掩码 */
                if (u <= 0 || u >= 256) continue;
                rpt[2 + (u / 8)] |= (uint8_t)(1 << (u % 8));
            }
        }
    }
    gkme_uk_send_input(dev->fd, rpt, 34);
}

/* ── JNI：鼠标 ────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeCreateMouseUhid(
        JNIEnv *env, jclass clazz) {
    return gkme_uk_create(GKMD_DESC_MOUSE, GKMD_DESC_MOUSE_LEN, "GKME HID Mouse",
                          "1337", 0x2E8A, 0x0011, GKME_PROFILE_MOUSE, 0);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeWriteMouseUhid(
        JNIEnv *env, jclass clazz, jint fd, jint dx, jint dy, jint wheel, jint pan,
        jint buttons) {
    gkme_uk_dev *dev = gkme_uk_get(fd);
    if (!dev) return;
    uint8_t rpt[9];
    memset(rpt, 0, sizeof(rpt));
    rpt[0] = (uint8_t)(buttons & 0xFF);
    rpt[1] = (uint8_t)(dx & 0xFF);
    rpt[2] = (uint8_t)((dx >> 8) & 0xFF);
    rpt[3] = (uint8_t)(dy & 0xFF);
    rpt[4] = (uint8_t)((dy >> 8) & 0xFF);
    rpt[5] = (uint8_t)(wheel & 0xFF);
    rpt[6] = (uint8_t)((wheel >> 8) & 0xFF);
    rpt[7] = (uint8_t)(pan & 0xFF);
    rpt[8] = (uint8_t)((pan >> 8) & 0xFF);
    gkme_uk_send_input(dev->fd, rpt, 9);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_controlled_RemoteGamepadDevice_nativeDestroyUhidInput(
        JNIEnv *env, jclass clazz, jint fd) {
    gkme_uk_destroy(fd);
}
