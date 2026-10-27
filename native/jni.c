// JNI bridge between the Kotlin app and whisper.cpp.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static volatile int g_abort = 0;

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID on_progress;
} cb_data;

static void progress_cb(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user) {
    (void) ctx; (void) state;
    cb_data *d = (cb_data *) user;
    if (d && d->listener && d->on_progress) {
        (*d->env)->CallVoidMethod(d->env, d->listener, d->on_progress, (jint) progress);
        if ((*d->env)->ExceptionCheck(d->env)) (*d->env)->ExceptionClear(d->env);
    }
}

static bool abort_cb(void *user) {
    (void) user;
    return g_abort != 0;
}

#define FN(name) Java_nl_collegewhispr_whisper_WhisperLib_##name

JNIEXPORT jlong JNICALL FN(initContext)(JNIEnv *env, jclass clazz, jstring model_path) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL FN(freeContext)(JNIEnv *env, jclass clazz, jlong ptr) {
    (void) env; (void) clazz;
    if (ptr) whisper_free((struct whisper_context *) ptr);
}

JNIEXPORT void JNICALL FN(requestAbort)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    g_abort = 1;
}

JNIEXPORT jstring JNICALL FN(systemInfo)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

// Returns UTF-8 bytes of lines: "t0_ms\tt1_ms\ttext\n". Returns null on failure/abort.
JNIEXPORT jbyteArray JNICALL FN(transcribe)(JNIEnv *env, jclass clazz, jlong ptr, jfloatArray audio,
                                          jstring language, jstring prompt, jstring vad_model,
                                          jint threads, jobject listener) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (!ctx) return NULL;
    g_abort = 0;

    jsize n = (*env)->GetArrayLength(env, audio);
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);

    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    const char *prm = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;
    const char *vad = vad_model ? (*env)->GetStringUTFChars(env, vad_model, NULL) : NULL;

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.translate = false;
    p.no_context = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.greedy.best_of = 2;
    p.language = (lang && strlen(lang) > 0) ? lang : "auto";
    p.detect_language = false;
    if (prm && strlen(prm) > 0) p.initial_prompt = prm;

    if (vad && strlen(vad) > 0) {
        p.vad = true;
        p.vad_model_path = vad;
        p.vad_params = whisper_vad_default_params();
        p.vad_params.min_silence_duration_ms = 500;
        p.vad_params.max_speech_duration_s = 28.0f;
        p.vad_params.speech_pad_ms = 200;
    }

    cb_data data;
    data.env = env;
    data.listener = listener;
    data.on_progress = NULL;
    if (listener) {
        jclass lc = (*env)->GetObjectClass(env, listener);
        data.on_progress = (*env)->GetMethodID(env, lc, "onProgress", "(I)V");
    }
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = &data;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = NULL;

    int rc = whisper_full(ctx, p, samples, n);

    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    if (prm) (*env)->ReleaseStringUTFChars(env, prompt, prm);
    if (vad) (*env)->ReleaseStringUTFChars(env, vad_model, vad);

    if (rc != 0 || g_abort) {
        LOGI("whisper_full failed rc=%d abort=%d", rc, g_abort);
        return NULL;
    }

    int ns = whisper_full_n_segments(ctx);
    size_t cap = 4096, len = 0;
    char *buf = malloc(cap);
    buf[0] = 0;
    for (int i = 0; i < ns; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        long long t0 = (long long) whisper_full_get_segment_t0(ctx, i) * 10;
        long long t1 = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        size_t need = strlen(t) + 64;
        if (len + need >= cap) {
            while (len + need >= cap) cap *= 2;
            buf = realloc(buf, cap);
        }
        // strip tabs/newlines from segment text
        len += (size_t) snprintf(buf + len, cap - len, "%lld\t%lld\t", t0, t1);
        for (const char *c = t; *c; c++) buf[len++] = (*c == '\t' || *c == '\n') ? ' ' : *c;
        buf[len++] = '\n';
        buf[len] = 0;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) buf);
    free(buf);
    return out;
}
