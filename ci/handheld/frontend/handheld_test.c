/*
 * CI test runner for the handheld cores through AceArcade's frontend (the same code the app uses).
 *
 *   handheld_test --core CORE.so --rom ROM [--system DIR] [--save DIR] [--option key=value]...
 *                 [--frames N] [--size WxH] [--pixel X,Y=RRGGBB] [--counter ADDR]
 *                 [--cheat CODE --marker ADDR=BYTE [--cheat-pixel X,Y=RRGGBB]] [--state] [--sram TEXT]
 *                 [--mem-word ADDR=VALUE (after the cheat phase)] [--dump-maps]
 *
 * Phases: run N frames (default 120) and check the picture size, a pixel and that a 32-bit counter in memory moves;
 * then enable the cheat, run 30 frames and check the marker byte (and pixel); then disable all cheats, clear the
 * marker, run 30 frames and check the cheat no longer applies; save a state, run, load it and check the counter
 * went back; check the battery save starts with TEXT. Prints PASS/FAIL lines; exit status 1 if anything failed.
 */
#include "frontend.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
#define CHECK(cond, ...) do { if (cond) { printf("PASS "); } else { printf("FAIL "); failures++; } printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)

static void logger(int level, const char *msg) { if (level >= 1) fprintf(stderr, "  [log %d] %s\n", level, msg); }

static uint32_t frame_buf[1024 * 1024];

static int pixel_at(unsigned x, unsigned y, unsigned *rgb) {
    unsigned w, h;
    fe_copy_frame(frame_buf, sizeof frame_buf / 4, &w, &h);
    if (x >= w || y >= h) return 0;
    uint32_t p = frame_buf[y * w + x];
    *rgb = ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
    return 1;
}

static int parse_pixel(const char *s, unsigned *x, unsigned *y, unsigned *rgb) {
    return s && sscanf(s, "%u,%u=%x", x, y, rgb) == 3;
}

static uint32_t read32(unsigned long long a) {
    uint32_t v = 0;
    if (fe_mem_read(a, &v, 4) != 4) return 0xDEADBEEF;
    return v;
}

int main(int argc, char **argv) {
    const char *corep = NULL, *rom = NULL, *sys = "/tmp", *save = "/tmp", *size = NULL, *pixel = NULL, *cheat = NULL;
    const char *marker = NULL, *cheat_pixel = NULL, *sram = NULL, *mem_word = NULL;
    unsigned long long counter = 0;
    int frames = 120, do_state = 0, dump = 0;
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i], *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!strcmp(a, "--core")) corep = v, i++;
        else if (!strcmp(a, "--rom")) rom = v, i++;
        else if (!strcmp(a, "--system")) sys = v, i++;
        else if (!strcmp(a, "--save")) save = v, i++;
        else if (!strcmp(a, "--frames")) frames = atoi(v), i++;
        else if (!strcmp(a, "--size")) size = v, i++;
        else if (!strcmp(a, "--pixel")) pixel = v, i++;
        else if (!strcmp(a, "--counter")) counter = strtoull(v, NULL, 0), i++;
        else if (!strcmp(a, "--cheat")) cheat = v, i++;
        else if (!strcmp(a, "--marker")) marker = v, i++;
        else if (!strcmp(a, "--cheat-pixel")) cheat_pixel = v, i++;
        else if (!strcmp(a, "--sram")) sram = v, i++;
        else if (!strcmp(a, "--mem-word")) mem_word = v, i++;
        else if (!strcmp(a, "--state")) do_state = 1;
        else if (!strcmp(a, "--dump-maps")) dump = 1;
        else if (!strcmp(a, "--option") && v) {
            char kv[512]; snprintf(kv, sizeof kv, "%s", v);
            char *eq = strchr(kv, '=');
            if (eq) { *eq = 0; fe_set_option(kv, eq + 1); }
            i++;
        } else { fprintf(stderr, "unknown argument %s\n", a); return 2; }
    }
    if (!corep || !rom) { fprintf(stderr, "--core and --rom are required\n"); return 2; }
    char err[512];
    printf("== %s with %s\n", rom, corep);
    if (fe_open(corep, sys, save, logger, err, sizeof err)) { printf("FAIL open core: %s\n", err); return 1; }
    if (fe_load_game(rom, err, sizeof err)) { printf("FAIL load game: %s\n", err); return 1; }
    double fps, rate; unsigned bw, bh; float aspect;
    fe_av_info(&fps, &rate, &bw, &bh, &aspect);
    CHECK(fps > 30 && fps < 70 && rate > 8000, "timing: %.4f fps, %.0f Hz audio, base %ux%u", fps, rate, bw, bh);
    if (dump) { char maps[4096]; fe_mem_describe(maps, sizeof maps); printf("memory maps:\n%s", maps); }

    uint32_t c0 = counter ? read32(counter) : 0;
    size_t audio = 0;
    int16_t abuf[8192];
    for (int f = 0; f < frames; f++) { fe_run(); audio += fe_take_audio(abuf, 4096); }
    unsigned w, h;
    uint64_t serial = fe_copy_frame(NULL, 0, &w, &h);
    CHECK(serial > 0 && w > 0 && h > 0, "picture: %llu frames drawn, %ux%u", (unsigned long long)serial, w, h);
    CHECK(audio > (size_t)(rate * frames / fps / 2), "sound: %zu stereo frames in %d frames", audio, frames);
    if (size) { unsigned ew, eh; sscanf(size, "%ux%u", &ew, &eh); CHECK(w == ew && h == eh, "picture size %ux%u (expected %s)", w, h, size); }
    unsigned px, py, prgb, got;
    if (parse_pixel(pixel, &px, &py, &prgb))
        CHECK(pixel_at(px, py, &got) && got == prgb, "pixel %u,%u = %06X (expected %06X)", px, py, got, prgb);
    if (counter) {
        uint32_t c1 = read32(counter);
        CHECK(c1 != 0xDEADBEEF && c1 != c0, "game running: counter at %08llX %08X -> %08X", counter, c0, c1);
    }

    if (cheat && marker) {
        unsigned long long maddr; unsigned mval;
        sscanf(marker, "%llx=%x", &maddr, &mval);
        const char *codes[1] = { cheat };
        fe_set_cheats(codes, 1);
        for (int f = 0; f < 30; f++) fe_run();
        uint8_t b = 0;
        size_t n = fe_mem_read(maddr, &b, 1);
        CHECK(n == 1 && b == mval, "cheat %s: byte at %08llX = %02X (expected %02X)", cheat, maddr, b, mval);
        if (parse_pixel(cheat_pixel, &px, &py, &prgb))
            CHECK(pixel_at(px, py, &got) && got == prgb, "cheat visible: pixel %u,%u = %06X (expected %06X)", px, py, got, prgb);
        if (mem_word) {
            unsigned long long wa; unsigned wv;
            sscanf(mem_word, "%llx=%x", &wa, &wv);
            uint32_t rv = read32(wa);
            CHECK(rv == wv, "cheat word at %08llX = %08X (expected %08X)", wa, rv, wv);
        }
        fe_set_cheats(NULL, 0);
        uint8_t zero = 0;
        fe_mem_write(maddr, &zero, 1);
        for (int f = 0; f < 30; f++) fe_run();
        b = 0xFF;
        fe_mem_read(maddr, &b, 1);
        CHECK(b == 0, "cheat off: byte at %08llX = %02X (expected 00)", maddr, b);
        if (parse_pixel(pixel, &px, &py, &prgb))
            CHECK(pixel_at(px, py, &got) && got == prgb, "cheat off: pixel %u,%u = %06X (expected %06X)", px, py, got, prgb);
    }

    if (do_state) {
        size_t sz = fe_state_size();
        void *st = sz ? malloc(sz) : NULL;
        int ok = st && fe_state_save(st, sz) == 0;
        CHECK(ok, "save state: %zu bytes", sz);
        if (ok && counter) {
            uint32_t at_save = read32(counter);
            for (int f = 0; f < 60; f++) fe_run();
            uint32_t later = read32(counter);
            int loaded = fe_state_load(st, sz) == 0;
            uint32_t after = read32(counter);
            CHECK(loaded && after == at_save && later != at_save, "load state: counter %08X, ran to %08X, back to %08X", at_save, later, after);
        }
        free(st);
    }

    if (sram) {
        size_t n = fe_sram_size();
        const char *d = fe_sram_data();
        size_t l = strlen(sram);
        CHECK(n >= l && d && memcmp(d, sram, l) == 0, "battery save: %zu bytes, starts with \"%.*s\" (expected \"%s\")",
              n, (int)(d && n >= l ? l : 0), d ? d : "", sram);
    }

    fe_close();
    printf("%s: %d failure(s)\n", rom, failures);
    return failures ? 1 : 0;
}
