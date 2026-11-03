/*
 * JNI bridge for com.acecore.app.runtime.handheld.HandheldCore.
 *
 * libretro cores must be driven from one thread, so everything that touches the core runs on the emulation thread
 * started here: frames, and every request from the app (load, battery save, states, cheats, memory) as a queued job
 * that the app waits for. Input, the latest frame and options are shared through locks/atomics. Sound goes to an
 * AAudio stream at the core's sample rate; frame pacing follows the core's frame rate and is nudged by how much
 * sound is waiting, so audio neither runs dry nor builds up latency.
 *
 * Copyright AceArcade contributors. GPL-3.0-or-later (see LICENSE).
 */
#define _GNU_SOURCE
#include "frontend.h"
#include "libretro.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <math.h>

#define TAG "AceArcadeHandheld"

static JavaVM *jvm;
static jobject callback_obj;      /* the HandheldCore instance */
static jmethodID on_frame_mid;    /* void onFrame(int rumble) */
static jmethodID on_log_mid;      /* void onLog(int level, String message) */

static void android_log(int level, const char *msg) {
    __android_log_print(level >= 3 ? ANDROID_LOG_ERROR : level == 2 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, TAG, "%s", msg);
    if (!jvm || !callback_obj || !on_log_mid || level < 1) return;
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || !env) return; /* only from attached threads */
    jstring s = (*env)->NewStringUTF(env, msg);
    if (!s) { (*env)->ExceptionClear(env); return; }
    (*env)->CallVoidMethod(env, callback_obj, on_log_mid, (jint)level, s);
    (*env)->DeleteLocalRef(env, s);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ------------------------------------------------------------------ worker thread and jobs */

typedef struct job {
    void (*fn)(struct job *);
    const char *s1, *s2, *s3;
    void *buf; size_t len; uint64_t addr;
    const char *const *codes; int count;
    int result; char err[512];
    void *out; size_t out_len;
    bool done;
    struct job *next;
} job_t;

static pthread_t worker;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
static job_t *queue_head, *queue_tail;
static volatile bool quit, running, game_loaded;
static volatile float speed = 1.0f;
static volatile float volume = 1.0f;
static volatile bool audio_paused;

static void submit(job_t *j) {
    pthread_mutex_lock(&lock);
    j->done = false; j->next = NULL;
    if (queue_tail) queue_tail->next = j; else queue_head = j;
    queue_tail = j;
    pthread_cond_broadcast(&cond);
    while (!j->done) pthread_cond_wait(&cond, &lock);
    pthread_mutex_unlock(&lock);
}

static void run_jobs(void) {
    for (;;) {
        pthread_mutex_lock(&lock);
        job_t *j = queue_head;
        if (j) { queue_head = j->next; if (!queue_head) queue_tail = NULL; }
        pthread_mutex_unlock(&lock);
        if (!j) return;
        j->fn(j);
        pthread_mutex_lock(&lock);
        j->done = true;
        pthread_cond_broadcast(&cond);
        pthread_mutex_unlock(&lock);
    }
}

/* ------------------------------------------------------------------ audio */

static AAudioStream *stream;
static int16_t *audio_scratch;
static size_t audio_scratch_frames;

static aaudio_data_callback_result_t audio_cb(AAudioStream *s, void *user, void *data, int32_t frames) {
    int16_t *out = data;
    size_t got = audio_paused ? 0 : fe_take_audio(out, (size_t)frames);
    float v = volume;
    if (v < 0.999f) for (size_t i = 0; i < got * 2; i++) out[i] = (int16_t)(out[i] * v);
    if (got < (size_t)frames) memset(out + got * 2, 0, ((size_t)frames - got) * 4);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void audio_open(int rate) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(b, audio_cb, NULL);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { __android_log_print(ANDROID_LOG_WARN, TAG, "audio stream: %s", AAudio_convertResultToText(r)); stream = NULL; return; }
    __android_log_print(ANDROID_LOG_INFO, TAG, "audio: %d Hz requested, %d Hz stream", rate, AAudioStream_getSampleRate(stream));
    AAudioStream_requestStart(stream);
}

static void audio_close(void) {
    if (stream) { AAudioStream_requestStop(stream); AAudioStream_close(stream); stream = NULL; }
    free(audio_scratch); audio_scratch = NULL; audio_scratch_frames = 0;
}

/* ------------------------------------------------------------------ emulation loop */

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void *worker_main(void *arg) {
    JNIEnv *env = NULL;
    (*jvm)->AttachCurrentThread(jvm, (void *)&env, NULL);
    double next = now_s();
    uint64_t last_serial = 0;
    while (!quit) {
        run_jobs();
        if (!running || !game_loaded || fe_shutdown_requested()) {
            pthread_mutex_lock(&lock);
            if (!queue_head && !quit) {
                struct timespec ts;
                clock_gettime(CLOCK_REALTIME, &ts);
                ts.tv_nsec += 20 * 1000000;
                if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
                pthread_cond_timedwait(&cond, &lock, &ts);
            }
            pthread_mutex_unlock(&lock);
            next = now_s();
            continue;
        }
        double fps, rate; unsigned bw, bh; float aspect;
        fe_av_info(&fps, &rate, &bw, &bh, &aspect);
        if (fps <= 0) fps = 60;
        float sp = speed;
        bool ff = sp > 1.01f;
        fe_set_fast_forward(ff);
        fe_set_audio_enabled(!ff); /* fast-forward is silent */
        fe_run();
        unsigned w, h;
        uint64_t serial = fe_copy_frame(NULL, 0, &w, &h);
        if (serial != last_serial && callback_obj) {
            last_serial = serial;
            (*env)->CallVoidMethod(env, callback_obj, on_frame_mid, (jint)fe_rumble());
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
        /* Pacing: one frame period (shorter while fast-forwarding), corrected by the sound backlog. */
        double period = 1.0 / (fps * sp);
        if (!ff && stream) {
            double target = rate * 0.06; /* ~60 ms of sound waiting */
            double pending = (double)fe_audio_pending();
            double err = (pending - target) / target;
            if (err > 1) err = 1; else if (err < -1) err = -1;
            period *= 1.0 + 0.01 * err;
        }
        next += period;
        double t = now_s();
        if (next < t - 0.1) next = t; /* fell behind (slow device or a pause): don't race to catch up */
        double wait = next - t;
        if (wait > 0) {
            struct timespec ts = { (time_t)wait, (long)((wait - (time_t)wait) * 1e9) };
            nanosleep(&ts, NULL);
        }
    }
    run_jobs();
    (*jvm)->DetachCurrentThread(jvm);
    return NULL;
}

/* ------------------------------------------------------------------ jobs */

static void job_open(job_t *j) { j->result = fe_open(j->s1, j->s2, j->s3, android_log, j->err, sizeof j->err); }
static void job_load(job_t *j) {
    j->result = fe_load_game(j->s1, j->err, sizeof j->err);
    if (j->result == 0) {
        double fps, rate; unsigned bw, bh; float a;
        fe_av_info(&fps, &rate, &bw, &bh, &a);
        audio_open((int)(rate + 0.5));
        game_loaded = true;
    }
}
static void job_sram_read(job_t *j) {
    size_t n = fe_sram_size();
    void *d = fe_sram_data();
    j->out_len = 0;
    if (n && d) { j->out = malloc(n); if (j->out) { memcpy(j->out, d, n); j->out_len = n; } }
}
static void job_sram_write(job_t *j) {
    size_t n = fe_sram_size();
    void *d = fe_sram_data();
    if (!d || !n) { j->result = -1; return; }
    memcpy(d, j->buf, j->len < n ? j->len : n);
    j->result = (int)(j->len < n ? j->len : n);
}
static void job_state_save(job_t *j) {
    size_t n = fe_state_size();
    j->out_len = 0;
    if (!n) return;
    j->out = malloc(n);
    if (j->out && fe_state_save(j->out, n) == 0) j->out_len = n; else { free(j->out); j->out = NULL; }
}
static void job_state_load(job_t *j) { j->result = fe_state_load(j->buf, j->len); }
static void job_cheats(job_t *j) { j->result = fe_set_cheats(j->codes, j->count); }
static void job_mem_read(job_t *j) {
    j->out = malloc(j->len ? j->len : 1);
    j->out_len = j->out ? fe_mem_read(j->addr, j->out, j->len) : 0;
}
static void job_mem_write(job_t *j) { j->result = (int)fe_mem_write(j->addr, j->buf, j->len); }
static void job_maps(job_t *j) { j->out = malloc(8192); j->out_len = j->out ? fe_mem_describe(j->out, 8192) : 0; }
static void job_reset(job_t *j) { fe_reset(); }
static void job_close(job_t *j) { game_loaded = false; audio_close(); fe_close(); }

/* ------------------------------------------------------------------ JNI */

#define FN(name) Java_com_acecore_app_runtime_handheld_HandheldCore_##name

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) { jvm = vm; return JNI_VERSION_1_6; }

JNIEXPORT void JNICALL FN(nCreate)(JNIEnv *env, jobject self) {
    callback_obj = (*env)->NewGlobalRef(env, self);
    jclass c = (*env)->GetObjectClass(env, self);
    on_frame_mid = (*env)->GetMethodID(env, c, "onFrame", "(I)V");
    on_log_mid = (*env)->GetMethodID(env, c, "onLog", "(ILjava/lang/String;)V");
    quit = false;
    pthread_create(&worker, NULL, worker_main, NULL);
}

static jstring err_or_null(JNIEnv *env, job_t *j) { return j->result == 0 ? NULL : (*env)->NewStringUTF(env, j->err); }

JNIEXPORT jstring JNICALL FN(nOpen)(JNIEnv *env, jobject self, jstring core, jstring sys, jstring save) {
    job_t j = { .fn = job_open };
    j.s1 = (*env)->GetStringUTFChars(env, core, NULL);
    j.s2 = (*env)->GetStringUTFChars(env, sys, NULL);
    j.s3 = (*env)->GetStringUTFChars(env, save, NULL);
    submit(&j);
    (*env)->ReleaseStringUTFChars(env, core, j.s1);
    (*env)->ReleaseStringUTFChars(env, sys, j.s2);
    (*env)->ReleaseStringUTFChars(env, save, j.s3);
    return err_or_null(env, &j);
}

JNIEXPORT void JNICALL FN(nSetOption)(JNIEnv *env, jobject self, jstring key, jstring value) {
    const char *k = (*env)->GetStringUTFChars(env, key, NULL), *v = (*env)->GetStringUTFChars(env, value, NULL);
    fe_set_option(k, v);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, value, v);
}

JNIEXPORT jstring JNICALL FN(nLoad)(JNIEnv *env, jobject self, jstring path) {
    job_t j = { .fn = job_load };
    j.s1 = (*env)->GetStringUTFChars(env, path, NULL);
    submit(&j);
    (*env)->ReleaseStringUTFChars(env, path, j.s1);
    return err_or_null(env, &j);
}

JNIEXPORT jdoubleArray JNICALL FN(nAvInfo)(JNIEnv *env, jobject self) {
    double fps, rate; unsigned w, h; float a;
    fe_av_info(&fps, &rate, &w, &h, &a);
    jdouble v[5] = { fps, rate, w, h, a };
    jdoubleArray arr = (*env)->NewDoubleArray(env, 5);
    (*env)->SetDoubleArrayRegion(env, arr, 0, 5, v);
    return arr;
}

JNIEXPORT void JNICALL FN(nSetRunning)(JNIEnv *env, jobject self, jboolean r) {
    running = r;
    audio_paused = !r;
    pthread_mutex_lock(&lock); pthread_cond_broadcast(&cond); pthread_mutex_unlock(&lock);
}
JNIEXPORT void JNICALL FN(nSetSpeed)(JNIEnv *env, jobject self, jfloat s) { speed = s < 0.25f ? 0.25f : s > 16 ? 16 : s; }
JNIEXPORT void JNICALL FN(nSetVolume)(JNIEnv *env, jobject self, jfloat v) { volume = v < 0 ? 0 : v > 1 ? 1 : v; }
JNIEXPORT void JNICALL FN(nButtons)(JNIEnv *env, jobject self, jint mask) { fe_set_buttons((uint32_t)mask); }
JNIEXPORT void JNICALL FN(nPointer)(JNIEnv *env, jobject self, jint x, jint y, jboolean pressed) { fe_set_pointer(x, y, pressed); }
JNIEXPORT void JNICALL FN(nAnalog)(JNIEnv *env, jobject self, jint index, jint id, jint value) { fe_set_analog(index, id, value); }

/* Copies the latest frame into a direct buffer (RGBA bytes). Returns (serial << 32) | (w << 16) | h, or 0. */
JNIEXPORT jlong JNICALL FN(nCopyFrame)(JNIEnv *env, jobject self, jobject buffer) {
    void *p = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!p || cap < 4) return 0;
    unsigned w = 0, h = 0;
    uint64_t s = fe_copy_frame(p, (size_t)cap / 4, &w, &h);
    if (!s || (size_t)w * h * 4 > (size_t)cap) return 0;
    return (jlong)(((s & 0x7FFFFFFF) << 32) | ((uint64_t)(w & 0xFFFF) << 16) | (h & 0xFFFF));
}

static jbyteArray out_bytes(JNIEnv *env, job_t *j) {
    if (!j->out || !j->out_len) { free(j->out); return NULL; }
    jbyteArray a = (*env)->NewByteArray(env, (jsize)j->out_len);
    if (a) (*env)->SetByteArrayRegion(env, a, 0, (jsize)j->out_len, j->out);
    free(j->out);
    return a;
}

JNIEXPORT jbyteArray JNICALL FN(nSramRead)(JNIEnv *env, jobject self) { job_t j = { .fn = job_sram_read }; submit(&j); return out_bytes(env, &j); }

JNIEXPORT jint JNICALL FN(nSramWrite)(JNIEnv *env, jobject self, jbyteArray data) {
    job_t j = { .fn = job_sram_write };
    j.len = (size_t)(*env)->GetArrayLength(env, data);
    j.buf = (*env)->GetByteArrayElements(env, data, NULL);
    submit(&j);
    (*env)->ReleaseByteArrayElements(env, data, j.buf, JNI_ABORT);
    return j.result;
}

JNIEXPORT jbyteArray JNICALL FN(nStateSave)(JNIEnv *env, jobject self) { job_t j = { .fn = job_state_save }; submit(&j); return out_bytes(env, &j); }

JNIEXPORT jboolean JNICALL FN(nStateLoad)(JNIEnv *env, jobject self, jbyteArray data) {
    job_t j = { .fn = job_state_load };
    j.len = (size_t)(*env)->GetArrayLength(env, data);
    j.buf = (*env)->GetByteArrayElements(env, data, NULL);
    submit(&j);
    (*env)->ReleaseByteArrayElements(env, data, j.buf, JNI_ABORT);
    return j.result == 0;
}

JNIEXPORT jint JNICALL FN(nSetCheats)(JNIEnv *env, jobject self, jobjectArray codes) {
    int n = codes ? (*env)->GetArrayLength(env, codes) : 0;
    const char **c = n ? calloc((size_t)n, sizeof *c) : NULL;
    jstring *refs = n ? calloc((size_t)n, sizeof *refs) : NULL;
    for (int i = 0; i < n; i++) {
        refs[i] = (*env)->GetObjectArrayElement(env, codes, i);
        c[i] = (*env)->GetStringUTFChars(env, refs[i], NULL);
    }
    job_t j = { .fn = job_cheats, .codes = c, .count = n };
    submit(&j);
    for (int i = 0; i < n; i++) { (*env)->ReleaseStringUTFChars(env, refs[i], c[i]); (*env)->DeleteLocalRef(env, refs[i]); }
    free(c); free(refs);
    return j.result;
}

JNIEXPORT jbyteArray JNICALL FN(nMemRead)(JNIEnv *env, jobject self, jlong addr, jint len) {
    job_t j = { .fn = job_mem_read, .addr = (uint64_t)addr, .len = len > 0 ? (size_t)len : 0 };
    submit(&j);
    return out_bytes(env, &j);
}

JNIEXPORT jint JNICALL FN(nMemWrite)(JNIEnv *env, jobject self, jlong addr, jbyteArray data) {
    job_t j = { .fn = job_mem_write, .addr = (uint64_t)addr };
    j.len = (size_t)(*env)->GetArrayLength(env, data);
    j.buf = (*env)->GetByteArrayElements(env, data, NULL);
    submit(&j);
    (*env)->ReleaseByteArrayElements(env, data, j.buf, JNI_ABORT);
    return j.result;
}

JNIEXPORT jstring JNICALL FN(nMemMaps)(JNIEnv *env, jobject self) {
    job_t j = { .fn = job_maps };
    submit(&j);
    jstring s = (*env)->NewStringUTF(env, j.out && j.out_len ? (const char *)j.out : "");
    free(j.out);
    return s;
}

JNIEXPORT void JNICALL FN(nReset)(JNIEnv *env, jobject self) { job_t j = { .fn = job_reset }; submit(&j); }

JNIEXPORT jstring JNICALL FN(nMessage)(JNIEnv *env, jobject self) { unsigned s; return (*env)->NewStringUTF(env, fe_last_message(&s)); }
JNIEXPORT jboolean JNICALL FN(nShutdownRequested)(JNIEnv *env, jobject self) { return fe_shutdown_requested() != 0; }
JNIEXPORT jint JNICALL FN(nMicRate)(JNIEnv *env, jobject self) { return (jint)fe_mic_rate(); }
JNIEXPORT jboolean JNICALL FN(nMicWanted)(JNIEnv *env, jobject self) { return fe_mic_wanted() != 0; }
JNIEXPORT void JNICALL FN(nMicPush)(JNIEnv *env, jobject self, jshortArray data, jint count) {
    jshort *p = (*env)->GetShortArrayElements(env, data, NULL);
    fe_mic_push(p, (size_t)count);
    (*env)->ReleaseShortArrayElements(env, data, p, JNI_ABORT);
}

JNIEXPORT void JNICALL FN(nDestroy)(JNIEnv *env, jobject self) {
    running = false;
    job_t j = { .fn = job_close };
    submit(&j);
    quit = true;
    pthread_mutex_lock(&lock); pthread_cond_broadcast(&cond); pthread_mutex_unlock(&lock);
    pthread_join(worker, NULL);
    if (callback_obj) { (*env)->DeleteGlobalRef(env, callback_obj); callback_obj = NULL; }
}
