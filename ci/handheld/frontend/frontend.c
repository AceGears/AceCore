/*
 * AceArcade handheld frontend (see frontend.h). Implements the libretro frontend side for the two bundled cores:
 * software video (XRGB8888 / RGB565 / 0RGB1555 converted to RGBA8888), stereo int16 audio into a ring buffer,
 * joypad + pointer + analog input, core options (v0 variables, v1 and v2 definitions; defaults come from the core and
 * AceArcade overrides them), memory maps, rumble, messages, microphone, save states and battery saves.
 *
 * Copyright AceArcade contributors. GPL-3.0-or-later (see LICENSE).
 */
#define _GNU_SOURCE
#include "frontend.h"
#include "libretro.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ------------------------------------------------------------------ core symbols */

typedef struct {
    void *lib;
    void (*init)(void);
    void (*deinit)(void);
    unsigned (*api_version)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*set_controller_port_device)(unsigned, unsigned);
    void (*reset)(void);
    void (*run)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    void (*cheat_reset)(void);
    void (*cheat_set)(unsigned, bool, const char *);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} core_t;

static core_t core;
static fe_log_fn log_fn;
static char system_dir[1024], save_dir[1024];
static bool initialized, loaded;
static struct retro_system_info sysinfo;
static struct retro_system_av_info av;
static void *rom_data;
static size_t rom_size;

static void flog(int level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (log_fn) log_fn(level, buf); else fprintf(stderr, "%s\n", buf);
}

/* ------------------------------------------------------------------ options */

#define MAX_OPTIONS 256
typedef struct { char *key; char *value; char *deflt; bool overridden; } option_t;
static option_t options[MAX_OPTIONS];
static int option_count;
static bool options_changed;
static pthread_mutex_t option_lock = PTHREAD_MUTEX_INITIALIZER;

static option_t *find_option(const char *key) {
    for (int i = 0; i < option_count; i++) if (strcmp(options[i].key, key) == 0) return &options[i];
    return NULL;
}

static option_t *add_option(const char *key) {
    option_t *o = find_option(key);
    if (o) return o;
    if (option_count >= MAX_OPTIONS) return NULL;
    o = &options[option_count++];
    memset(o, 0, sizeof *o);
    o->key = strdup(key);
    return o;
}

/* A default from the core: kept unless AceArcade set the option. */
static void core_default(const char *key, const char *value) {
    if (!key || !value) return;
    option_t *o = add_option(key);
    if (!o) return;
    free(o->deflt);
    o->deflt = strdup(value);
    if (!o->overridden) { free(o->value); o->value = strdup(value); }
}

void fe_set_option(const char *key, const char *value) {
    pthread_mutex_lock(&option_lock);
    option_t *o = add_option(key);
    if (o) {
        free(o->value);
        o->value = strdup(value);
        o->overridden = true;
        options_changed = true;
    }
    pthread_mutex_unlock(&option_lock);
}

const char *fe_get_option(const char *key) {
    option_t *o = find_option(key);
    return o ? o->value : NULL;
}

/* ------------------------------------------------------------------ video */

static enum retro_pixel_format pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static pthread_mutex_t frame_lock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t *frame;
static size_t frame_cap;
static unsigned frame_w, frame_h;
static uint64_t frame_serial;

static inline uint32_t rgba(unsigned r, unsigned g, unsigned b) {
    return 0xFF000000u | (b << 16) | (g << 8) | r; /* bytes R,G,B,A in little-endian memory */
}

static void RETRO_CALLCONV video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !width || !height) return; /* dupe: keep the previous frame */
    pthread_mutex_lock(&frame_lock);
    size_t need = (size_t)width * height;
    if (need > frame_cap) {
        uint32_t *n = realloc(frame, need * 4);
        if (!n) { pthread_mutex_unlock(&frame_lock); return; }
        frame = n; frame_cap = need;
    }
    for (unsigned y = 0; y < height; y++) {
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        uint32_t *out = frame + (size_t)y * width;
        if (pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *p = (const uint32_t *)row;
            for (unsigned x = 0; x < width; x++) { uint32_t c = p[x]; out[x] = rgba((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF); }
        } else if (pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *p = (const uint16_t *)row;
            for (unsigned x = 0; x < width; x++) {
                uint16_t c = p[x];
                unsigned r = (c >> 11) & 0x1F, g = (c >> 5) & 0x3F, b = c & 0x1F;
                out[x] = rgba((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2));
            }
        } else {
            const uint16_t *p = (const uint16_t *)row;
            for (unsigned x = 0; x < width; x++) {
                uint16_t c = p[x];
                unsigned r = (c >> 10) & 0x1F, g = (c >> 5) & 0x1F, b = c & 0x1F;
                out[x] = rgba((r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2));
            }
        }
    }
    frame_w = width; frame_h = height;
    frame_serial++;
    pthread_mutex_unlock(&frame_lock);
}

uint64_t fe_copy_frame(uint32_t *out, size_t cap, unsigned *w, unsigned *h) {
    pthread_mutex_lock(&frame_lock);
    uint64_t s = frame_serial;
    *w = frame_w; *h = frame_h;
    size_t n = (size_t)frame_w * frame_h;
    if (out && n <= cap && frame) memcpy(out, frame, n * 4);
    pthread_mutex_unlock(&frame_lock);
    return s;
}

/* ------------------------------------------------------------------ audio (ring buffer of stereo frames) */

#define AUDIO_RING (1 << 15) /* frames; ~1 s at 32 kHz */
static int16_t audio_ring[AUDIO_RING * 2];
static size_t audio_head, audio_tail; /* frames, modulo AUDIO_RING */
static pthread_mutex_t audio_lock = PTHREAD_MUTEX_INITIALIZER;
static bool audio_enabled = true;

static size_t audio_count_locked(void) { return (audio_head - audio_tail) & (AUDIO_RING - 1); }

static size_t RETRO_CALLCONV audio_batch(const int16_t *data, size_t frames) {
    if (!audio_enabled) return frames;
    pthread_mutex_lock(&audio_lock);
    for (size_t i = 0; i < frames; i++) {
        if (audio_count_locked() == AUDIO_RING - 1) audio_tail = (audio_tail + 1) & (AUDIO_RING - 1); /* drop oldest */
        audio_ring[audio_head * 2] = data[i * 2];
        audio_ring[audio_head * 2 + 1] = data[i * 2 + 1];
        audio_head = (audio_head + 1) & (AUDIO_RING - 1);
    }
    pthread_mutex_unlock(&audio_lock);
    return frames;
}

static void RETRO_CALLCONV audio_sample(int16_t left, int16_t right) {
    int16_t f[2] = { left, right };
    audio_batch(f, 1);
}

size_t fe_take_audio(int16_t *out, size_t max_frames) {
    pthread_mutex_lock(&audio_lock);
    size_t n = audio_count_locked();
    if (n > max_frames) n = max_frames;
    for (size_t i = 0; i < n; i++) {
        out[i * 2] = audio_ring[audio_tail * 2];
        out[i * 2 + 1] = audio_ring[audio_tail * 2 + 1];
        audio_tail = (audio_tail + 1) & (AUDIO_RING - 1);
    }
    pthread_mutex_unlock(&audio_lock);
    return n;
}

size_t fe_audio_pending(void) {
    pthread_mutex_lock(&audio_lock);
    size_t n = audio_count_locked();
    pthread_mutex_unlock(&audio_lock);
    return n;
}

void fe_set_audio_enabled(int enabled) { audio_enabled = enabled != 0; }

/* ------------------------------------------------------------------ input */

static volatile uint32_t buttons;
static volatile int pointer_x, pointer_y, pointer_pressed;
static volatile int analog[2][2];
static volatile bool fast_forward;

void fe_set_buttons(uint32_t mask) { buttons = mask; }
void fe_set_pointer(int x, int y, int pressed) { pointer_x = x; pointer_y = y; pointer_pressed = pressed; }
void fe_set_analog(int index, int id, int value) { if (index >= 0 && index < 2 && id >= 0 && id < 2) analog[index][id] = value; }
void fe_set_fast_forward(int on) { fast_forward = on != 0; }

static void RETRO_CALLCONV input_poll(void) {}

static int16_t RETRO_CALLCONV input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    switch (device & RETRO_DEVICE_MASK) {
    case RETRO_DEVICE_JOYPAD:
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(buttons & 0xFFFF);
        return (int16_t)((buttons >> id) & 1);
    case RETRO_DEVICE_POINTER:
        if (index != 0) return 0;
        if (id == RETRO_DEVICE_ID_POINTER_X) return (int16_t)pointer_x;
        if (id == RETRO_DEVICE_ID_POINTER_Y) return (int16_t)pointer_y;
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return (int16_t)(pointer_pressed ? 1 : 0);
        if (id == RETRO_DEVICE_ID_POINTER_COUNT) return (int16_t)(pointer_pressed ? 1 : 0);
        return 0;
    case RETRO_DEVICE_ANALOG:
        if (index < 2 && id < 2) return (int16_t)analog[index][id];
        return 0;
    default:
        return 0;
    }
}

/* ------------------------------------------------------------------ memory maps */

#define MAX_DESCRIPTORS 64
static struct retro_memory_descriptor descriptors[MAX_DESCRIPTORS];
static unsigned descriptor_count;

/* libretro address translation (the "disconnect" bits are removed from the address before indexing). */
static uint64_t reduce(uint64_t addr, uint64_t mask) {
    while (mask) {
        uint64_t tmp = (mask - 1) & ~mask;
        addr = (addr & tmp) | ((addr >> 1) & ~tmp);
        mask = (mask & (mask - 1)) >> 1;
    }
    return addr;
}

static uint8_t *map_address(uint64_t addr, size_t *avail) {
    for (unsigned i = 0; i < descriptor_count; i++) {
        const struct retro_memory_descriptor *d = &descriptors[i];
        if (!d->ptr) continue;
        uint64_t select = d->select;
        if (!select) {
            /* No select mask: the descriptor covers [start, start + len). */
            if (addr < d->start || addr >= d->start + d->len) continue;
            uint64_t off = addr - d->start;
            *avail = d->len - off;
            return (uint8_t *)d->ptr + d->offset + off;
        }
        if ((addr & select) != (d->start & select)) continue;
        uint64_t off = reduce(addr & ~d->disconnect, d->disconnect) - reduce(d->start & ~d->disconnect, d->disconnect);
        if (d->len && off >= d->len) off %= d->len;
        *avail = d->len ? d->len - off : 1;
        return (uint8_t *)d->ptr + d->offset + off;
    }
    return NULL;
}

size_t fe_mem_read(uint64_t address, void *out, size_t len) {
    size_t done = 0;
    while (done < len) {
        size_t avail = 0;
        uint8_t *p = map_address(address + done, &avail);
        if (!p || !avail) break;
        size_t n = len - done < avail ? len - done : avail;
        memcpy((uint8_t *)out + done, p, n);
        done += n;
    }
    return done;
}

size_t fe_mem_write(uint64_t address, const void *in, size_t len) {
    size_t done = 0;
    while (done < len) {
        size_t avail = 0;
        uint8_t *p = map_address(address + done, &avail);
        if (!p || !avail) break;
        size_t n = len - done < avail ? len - done : avail;
        memcpy(p, (const uint8_t *)in + done, n);
        done += n;
    }
    return done;
}

size_t fe_mem_describe(char *buf, size_t cap) {
    size_t used = 0;
    if (cap) buf[0] = 0;
    for (unsigned i = 0; i < descriptor_count && used < cap; i++) {
        const struct retro_memory_descriptor *d = &descriptors[i];
        int n = snprintf(buf + used, cap - used, "%08llX len=%llX select=%llX disconnect=%llX flags=%llX %s%s\n",
                         (unsigned long long)d->start, (unsigned long long)d->len, (unsigned long long)d->select,
                         (unsigned long long)d->disconnect, (unsigned long long)d->flags, d->addrspace ? d->addrspace : "",
                         d->ptr ? "" : " (no pointer)");
        if (n < 0) break;
        used += (size_t)n;
    }
    return used;
}

/* ------------------------------------------------------------------ rumble, messages, microphone */

static volatile unsigned rumble_strong, rumble_weak;
static bool RETRO_CALLCONV set_rumble_state(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    if (port != 0) return false;
    if (effect == RETRO_RUMBLE_STRONG) rumble_strong = strength; else rumble_weak = strength;
    return true;
}
unsigned fe_rumble(void) { return rumble_strong > rumble_weak ? rumble_strong : rumble_weak; }

static char last_message[512];
static unsigned message_serial;
const char *fe_last_message(unsigned *serial) { *serial = message_serial; return last_message; }
static void set_message(const char *msg) {
    if (!msg || !strcmp(msg, last_message)) return; /* melonDS DS repeats status messages every frame */
    snprintf(last_message, sizeof last_message, "%s", msg);
    message_serial++;
    flog(1, "core message: %s", msg);
}

static volatile bool shutdown_requested;
int fe_shutdown_requested(void) { return shutdown_requested; }

/* One microphone: the app pushes recorded samples, the core reads them while the mic is "on". */
struct retro_microphone { unsigned rate; bool on; };
static struct retro_microphone the_mic;
static bool mic_open;
#define MIC_RING (1 << 14)
static int16_t mic_ring[MIC_RING];
static size_t mic_head, mic_tail;
static pthread_mutex_t mic_lock = PTHREAD_MUTEX_INITIALIZER;

static retro_microphone_t *RETRO_CALLCONV mic_open_fn(const retro_microphone_params_t *params) {
    if (mic_open) return NULL;
    the_mic.rate = params && params->rate ? params->rate : 44100;
    the_mic.on = false;
    mic_open = true;
    return &the_mic;
}
static void RETRO_CALLCONV mic_close_fn(retro_microphone_t *m) { if (m == &the_mic) mic_open = false; }
static bool RETRO_CALLCONV mic_get_params_fn(const retro_microphone_t *m, retro_microphone_params_t *p) {
    if (m != &the_mic || !p) return false;
    p->rate = the_mic.rate;
    return true;
}
static bool RETRO_CALLCONV mic_set_state_fn(retro_microphone_t *m, bool state) { if (m != &the_mic) return false; the_mic.on = state; return true; }
static bool RETRO_CALLCONV mic_get_state_fn(const retro_microphone_t *m) { return m == &the_mic && the_mic.on; }
static int RETRO_CALLCONV mic_read_fn(retro_microphone_t *m, int16_t *samples, size_t n) {
    if (m != &the_mic || !the_mic.on) return -1;
    pthread_mutex_lock(&mic_lock);
    size_t i = 0;
    for (; i < n && mic_tail != mic_head; i++) { samples[i] = mic_ring[mic_tail]; mic_tail = (mic_tail + 1) & (MIC_RING - 1); }
    pthread_mutex_unlock(&mic_lock);
    for (; i < n; i++) samples[i] = 0; /* not enough recorded yet: silence */
    return (int)n;
}
void fe_mic_push(const int16_t *samples, size_t count) {
    pthread_mutex_lock(&mic_lock);
    for (size_t i = 0; i < count; i++) {
        size_t next = (mic_head + 1) & (MIC_RING - 1);
        if (next == mic_tail) mic_tail = (mic_tail + 1) & (MIC_RING - 1);
        mic_ring[mic_head] = samples[i];
        mic_head = next;
    }
    pthread_mutex_unlock(&mic_lock);
}
unsigned fe_mic_rate(void) { return mic_open ? the_mic.rate : 0; }
int fe_mic_wanted(void) { return mic_open && the_mic.on; }

/* ------------------------------------------------------------------ environment */

static retro_usec_t frame_time_reference;
static retro_frame_time_callback_t frame_time_cb;

static void RETRO_CALLCONV core_log(enum retro_log_level level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    size_t n = strlen(buf);
    while (n && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    if (log_fn) log_fn((int)level, buf); else fprintf(stderr, "[core %d] %s\n", (int)level, buf);
}

static retro_time_t RETRO_CALLCONV perf_get_time_usec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (retro_time_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}
static retro_perf_tick_t RETRO_CALLCONV perf_get_counter(void) { return (retro_perf_tick_t)perf_get_time_usec(); }
static uint64_t RETRO_CALLCONV perf_get_cpu_features(void) { return 0; }
static void RETRO_CALLCONV perf_log(void) {}
static void RETRO_CALLCONV perf_register(struct retro_perf_counter *c) { c->registered = true; }
static void RETRO_CALLCONV perf_start(struct retro_perf_counter *c) { c->start = perf_get_counter(); }
static void RETRO_CALLCONV perf_stop(struct retro_perf_counter *c) { c->total += perf_get_counter() - c->start; }

static void RETRO_CALLCONV led_set_state(int led, int state) {}

static bool RETRO_CALLCONV environment(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        enum retro_pixel_format f = *(const enum retro_pixel_format *)data;
        if (f > RETRO_PIXEL_FORMAT_RGB565) return false;
        pixel_format = f; return true;
    }
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = system_dir; return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = save_dir; return true;
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        *(const char **)data = system_dir; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = core_log; return true;
    case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
        struct retro_perf_callback *p = data;
        p->get_time_usec = perf_get_time_usec; p->get_cpu_features = perf_get_cpu_features;
        p->get_perf_counter = perf_get_counter; p->perf_register = perf_register;
        p->perf_start = perf_start; p->perf_stop = perf_stop; p->perf_log = perf_log;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = data;
        pthread_mutex_lock(&option_lock);
        option_t *o = v->key ? find_option(v->key) : NULL;
        v->value = o ? o->value : NULL;
        pthread_mutex_unlock(&option_lock);
        return o != NULL && o->value != NULL;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
        pthread_mutex_lock(&option_lock);
        *(bool *)data = options_changed;
        options_changed = false;
        pthread_mutex_unlock(&option_lock);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        /* v0: "Description; first|second|..." - the first value is the default. */
        for (const struct retro_variable *v = data; v && v->key; v++) {
            const char *semi = v->value ? strchr(v->value, ';') : NULL;
            if (!semi) continue;
            const char *start = semi + 1;
            while (*start == ' ') start++;
            const char *bar = strchr(start, '|');
            size_t n = bar ? (size_t)(bar - start) : strlen(start);
            char buf[256];
            if (n >= sizeof buf) n = sizeof buf - 1;
            memcpy(buf, start, n); buf[n] = 0;
            core_default(v->key, buf);
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *(unsigned *)data = 2; return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
        for (const struct retro_core_option_definition *d = data; d && d->key; d++)
            core_default(d->key, d->default_value ? d->default_value : d->values[0].value);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
        const struct retro_core_options_intl *i = data;
        for (const struct retro_core_option_definition *d = i ? i->us : NULL; d && d->key; d++)
            core_default(d->key, d->default_value ? d->default_value : d->values[0].value);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
        const struct retro_core_options_v2 *o = data;
        for (const struct retro_core_option_v2_definition *d = o ? o->definitions : NULL; d && d->key; d++)
            core_default(d->key, d->default_value ? d->default_value : d->values[0].value);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
        const struct retro_core_options_v2_intl *i = data;
        const struct retro_core_options_v2 *o = i ? i->us : NULL;
        for (const struct retro_core_option_v2_definition *d = o ? o->definitions : NULL; d && d->key; d++)
            core_default(d->key, d->default_value ? d->default_value : d->values[0].value);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
    case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
    case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK:
    case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
    case RETRO_ENVIRONMENT_SET_PROC_ADDRESS_CALLBACK:
        return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        return true;
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: {
        const struct retro_memory_map *m = data;
        descriptor_count = 0;
        for (unsigned i = 0; m && i < m->num_descriptors && descriptor_count < MAX_DESCRIPTORS; i++)
            descriptors[descriptor_count++] = m->descriptors[i];
        return true;
    }
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        ((struct retro_rumble_interface *)data)->set_rumble_state = set_rumble_state; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
        *(uint64_t *)data = (1 << RETRO_DEVICE_JOYPAD) | (1 << RETRO_DEVICE_POINTER) | (1 << RETRO_DEVICE_ANALOG); return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        av = *(const struct retro_system_av_info *)data; return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        av.geometry = *(const struct retro_game_geometry *)data; return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        *(unsigned *)data = RETRO_LANGUAGE_ENGLISH; return true;
    case RETRO_ENVIRONMENT_GET_USERNAME:
        *(const char **)data = "AceArcade"; return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
        *(int *)data = 3; return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
        *(bool *)data = fast_forward; return true;
    case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
        *(float *)data = 60.0f; return true;
    case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
        struct retro_throttle_state *t = data;
        t->mode = fast_forward ? RETRO_THROTTLE_FAST_FORWARD : RETRO_THROTTLE_NONE;
        t->rate = fast_forward ? 0.0f : (float)av.timing.fps;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK: {
        const struct retro_frame_time_callback *c = data;
        frame_time_cb = c->callback; frame_time_reference = c->reference;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE:
        set_message(((const struct retro_message *)data)->msg); return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
        set_message(((const struct retro_message_ext *)data)->msg); return true;
    case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
        *(unsigned *)data = 1; return true;
    case RETRO_ENVIRONMENT_GET_LED_INTERFACE:
        ((struct retro_led_interface *)data)->set_led_state = led_set_state; return true;
    case RETRO_ENVIRONMENT_GET_JIT_CAPABLE:
        *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE: {
        struct retro_microphone_interface *m = data;
        if (!m || m->interface_version != RETRO_MICROPHONE_INTERFACE_VERSION) {
            if (m) m->interface_version = RETRO_MICROPHONE_INTERFACE_VERSION;
            return false;
        }
        m->open_mic = mic_open_fn; m->close_mic = mic_close_fn; m->get_params = mic_get_params_fn;
        m->set_mic_state = mic_set_state_fn; m->get_mic_state = mic_get_state_fn; m->read_mic = mic_read_fn;
        return true;
    }
    case RETRO_ENVIRONMENT_SHUTDOWN:
        flog(2, "the emulator stopped (fatal error)");
        shutdown_requested = true;
        return true;
    default:
        return false; /* VFS, hardware rendering, sensors, disk control ...: not provided */
    }
}

/* ------------------------------------------------------------------ lifecycle */

#define SYM(name) do { *(void **)&core.name = dlsym(core.lib, "retro_" #name); \
    if (!core.name) { snprintf(err, errlen, "core has no retro_" #name); dlclose(core.lib); core.lib = NULL; return -1; } } while (0)

int fe_open(const char *core_path, const char *sysdir, const char *savedir, fe_log_fn log, char *err, size_t errlen) {
    log_fn = log;
    snprintf(system_dir, sizeof system_dir, "%s", sysdir);
    snprintf(save_dir, sizeof save_dir, "%s", savedir);
    memset(&core, 0, sizeof core);
    core.lib = dlopen(core_path, RTLD_NOW | RTLD_LOCAL);
    if (!core.lib) { snprintf(err, errlen, "can't load %s: %s", core_path, dlerror()); return -1; }
    SYM(init); SYM(deinit); SYM(api_version); SYM(get_system_info); SYM(get_system_av_info); SYM(set_environment);
    SYM(set_video_refresh); SYM(set_audio_sample); SYM(set_audio_sample_batch); SYM(set_input_poll); SYM(set_input_state);
    SYM(set_controller_port_device); SYM(reset); SYM(run); SYM(serialize_size); SYM(serialize); SYM(unserialize);
    SYM(cheat_reset); SYM(cheat_set); SYM(load_game); SYM(unload_game); SYM(get_memory_data); SYM(get_memory_size);
    if (core.api_version() != RETRO_API_VERSION) { snprintf(err, errlen, "core API version %u", core.api_version()); return -1; }
    core.set_environment(environment);
    core.set_video_refresh(video_refresh);
    core.set_audio_sample(audio_sample);
    core.set_audio_sample_batch(audio_batch);
    core.set_input_poll(input_poll);
    core.set_input_state(input_state);
    core.get_system_info(&sysinfo);
    core.init();
    initialized = true;
    flog(1, "core %s %s loaded (need_fullpath=%d, extensions %s)", sysinfo.library_name, sysinfo.library_version,
         sysinfo.need_fullpath, sysinfo.valid_extensions ? sysinfo.valid_extensions : "");
    return 0;
}

int fe_load_game(const char *rom_path, char *err, size_t errlen) {
    if (!initialized) { snprintf(err, errlen, "no core"); return -1; }
    struct retro_game_info info = { rom_path, NULL, 0, NULL };
    if (!sysinfo.need_fullpath) {
        FILE *f = fopen(rom_path, "rb");
        if (!f) { snprintf(err, errlen, "can't open %s", rom_path); return -1; }
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (n <= 0) { fclose(f); snprintf(err, errlen, "%s is empty", rom_path); return -1; }
        rom_data = malloc((size_t)n);
        if (!rom_data || fread(rom_data, 1, (size_t)n, f) != (size_t)n) {
            fclose(f); free(rom_data); rom_data = NULL; snprintf(err, errlen, "can't read %s", rom_path); return -1;
        }
        fclose(f);
        rom_size = (size_t)n;
        info.data = rom_data; info.size = rom_size;
    }
    if (!core.load_game(&info)) { snprintf(err, errlen, "the emulator couldn't load the game"); free(rom_data); rom_data = NULL; return -1; }
    loaded = true;
    /* melonDS DS loads "successfully" onto an error screen when it can't start the ROM (e.g. an encrypted ROM
       without a BIOS); it then reports no system RAM. */
    if (sysinfo.library_name && strstr(sysinfo.library_name, "melonDS") && core.get_memory_size(RETRO_MEMORY_SYSTEM_RAM) == 0) {
        snprintf(err, errlen, "the DS emulator couldn't start this ROM%s%s", last_message[0] ? ": " : "", last_message);
        core.unload_game(); loaded = false; free(rom_data); rom_data = NULL;
        return -1;
    }
    /* Report the options as changed once, so mGBA applies the ones it only reads on updates. */
    pthread_mutex_lock(&option_lock); options_changed = true; pthread_mutex_unlock(&option_lock);
    core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    core.get_system_av_info(&av);
    flog(1, "game loaded: %ux%u (max %ux%u) %.4f fps, %.0f Hz", av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height, av.timing.fps, av.timing.sample_rate);
    return 0;
}

void fe_av_info(double *fps, double *rate, unsigned *base_w, unsigned *base_h, float *aspect) {
    *fps = av.timing.fps; *rate = av.timing.sample_rate;
    *base_w = av.geometry.base_width; *base_h = av.geometry.base_height;
    *aspect = av.geometry.aspect_ratio;
}

void fe_run(void) {
    if (!loaded || shutdown_requested) return;
    if (frame_time_cb) frame_time_cb(frame_time_reference);
    core.run();
}

void fe_reset(void) { if (loaded) core.reset(); }

int fe_set_cheats(const char *const *codes, int count) {
    if (!loaded) return 0;
    core.cheat_reset();
    for (int i = 0; i < count; i++) core.cheat_set((unsigned)i, true, codes[i]);
    return count;
}

size_t fe_state_size(void) { return loaded ? core.serialize_size() : 0; }
int fe_state_save(void *buf, size_t len) { return loaded && core.serialize(buf, len) ? 0 : -1; }
int fe_state_load(const void *buf, size_t len) { return loaded && core.unserialize(buf, len) ? 0 : -1; }

/* Battery save size. mGBA keeps a Game Boy cartridge clock (MBC3 RTC) right after the save RAM; it is included, which
   is mGBA's own .sav layout (save RAM, then 48 bytes of clock). Re-query before each save: sizes can change once the
   game first uses its save memory. */
size_t fe_sram_size(void) {
    if (!loaded) return 0;
    size_t n = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *s = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    uint8_t *r = core.get_memory_data(RETRO_MEMORY_RTC);
    size_t rn = core.get_memory_size(RETRO_MEMORY_RTC);
    if (s && r && rn && r == s + n) n += rn;
    return n;
}
void *fe_sram_data(void) { return loaded ? core.get_memory_data(RETRO_MEMORY_SAVE_RAM) : NULL; }

void fe_unload(void) {
    if (loaded) { core.unload_game(); loaded = false; }
    free(rom_data); rom_data = NULL; rom_size = 0;
    descriptor_count = 0;
}

void fe_close(void) {
    fe_unload();
    if (initialized) { core.deinit(); initialized = false; }
    /* The core library stays loaded: several cores keep static state that doesn't survive dlclose + dlopen. */
}
