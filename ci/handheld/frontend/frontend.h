/*
 * AceArcade handheld frontend: hosts one libretro core (mGBA or melonDS DS) at a time.
 * Portable C; android_jni.c exposes it to the app, handheld_test.c drives it in CI.
 * libretro cores keep global state, so there is one frontend per process.
 */
#ifndef ACEARCADE_FRONTEND_H
#define ACEARCADE_FRONTEND_H
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*fe_log_fn)(int level, const char *message);

/* Loads the core library and calls retro_init. Returns 0 on success; otherwise writes a reason to err. */
int fe_open(const char *core_path, const char *system_dir, const char *save_dir, fe_log_fn log, char *err, size_t errlen);
/* Sets a core option (before fe_load_game, or later: the core sees the change at its next check). */
void fe_set_option(const char *key, const char *value);
const char *fe_get_option(const char *key);
/* Loads a ROM (by path; the data is also read into memory for cores that want it). Returns 0 on success. */
int fe_load_game(const char *rom_path, char *err, size_t errlen);
void fe_unload(void);
void fe_close(void);

void fe_av_info(double *fps, double *sample_rate, unsigned *base_w, unsigned *base_h, float *aspect);
/* Runs one frame. */
void fe_run(void);
void fe_reset(void);

/*
 * The latest frame, converted to RGBA8888 bytes (R,G,B,A in memory: an Android ARGB_8888 bitmap's layout) with no
 * row padding. Copies at most cap pixels into out; returns the frame counter (increases with each new frame) and
 * sets *w, *h. Returns 0 before the first frame.
 */
uint64_t fe_copy_frame(uint32_t *out, size_t cap, unsigned *w, unsigned *h);

/* Audio produced since the last call: up to max_frames stereo int16 frames. Returns frames copied. */
size_t fe_take_audio(int16_t *out, size_t max_frames);
/* Frames of audio waiting. */
size_t fe_audio_pending(void);
void fe_set_audio_enabled(int enabled);

/* Input: RETRO_DEVICE_ID_JOYPAD_* bits for port 0; pointer in libretro coordinates (-0x7FFF..0x7FFF). */
void fe_set_buttons(uint32_t mask);
void fe_set_pointer(int x, int y, int pressed);
void fe_set_analog(int index, int id, int value);
void fe_set_fast_forward(int on);

/* Cheats: replaces the active list (each a code in the libretro "+"-joined form). Returns codes sent. */
int fe_set_cheats(const char *const *codes, int count);

/* Save states. fe_state_size may be 0 until a frame has run. */
size_t fe_state_size(void);
int fe_state_save(void *buf, size_t len);
int fe_state_load(const void *buf, size_t len);

/* Battery save (cartridge RAM) as the core exposes it; size 0 when the core manages its own save file. */
size_t fe_sram_size(void);
void *fe_sram_data(void);

/* Emulated memory by bus address (through the core's memory maps). Returns bytes read/written (0 = unmapped). */
size_t fe_mem_read(uint64_t address, void *out, size_t len);
size_t fe_mem_write(uint64_t address, const void *in, size_t len);
/* Describes the memory maps ("start-end name" lines) into buf. */
size_t fe_mem_describe(char *buf, size_t cap);

/* True after the core reported a fatal error (it no longer runs). */
int fe_shutdown_requested(void);
/* Rumble strength 0..65535 (strongest of the two motors) requested by the game. */
unsigned fe_rumble(void);
/* Last on-screen message the core asked to show ("" if none), and a counter that changes with each new message. */
const char *fe_last_message(unsigned *serial);

/* Microphone input for cores that ask for it: mono int16 samples at the rate in fe_mic_rate (0 = not opened). */
void fe_mic_push(const int16_t *samples, size_t count);
unsigned fe_mic_rate(void);
int fe_mic_wanted(void);

#ifdef __cplusplus
}
#endif
#endif
