/*
 * JNI bridge to libopus.
 *
 * Deliberately thin: it owns no policy, only the calls Kotlin cannot make.
 * Every parameter -- rate, frame size, bitrate, complexity, FEC -- is decided
 * in Kotlin against SaikaiConfig, so that ADR-004's audio settings live in one
 * readable place instead of half of them here in C.
 *
 * Nothing throws. Every entry point returns a status the caller can act on: a
 * codec failure mid-transmission should cost one frame, not the process. The
 * negative values are Opus error codes, which the Kotlin side reports as they
 * are rather than flattening -- OPUS_BAD_ARG and OPUS_INVALID_STATE mean very
 * different things when a bug report arrives.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <opus.h>

/*
 * Largest packet Opus will ever emit. Frames are capped far below this by the
 * protocol (400 bytes, ADR-003 section 1) and measured at 50, but the scratch
 * buffer is sized to what the library can legally produce so that a
 * misconfiguration is a rejected frame rather than a smashed stack.
 */
#define SAIKAI_MAX_PACKET 1275

/*
 * One 60 ms frame at 48 kHz, the most opus_decode can be asked for. Ours are
 * 320 mono samples; this is headroom, not an expectation.
 */
#define SAIKAI_MAX_FRAME_SAMPLES 5760

static jlong to_handle(void *pointer) {
    return (jlong) (intptr_t) pointer;
}

static void *from_handle(jlong handle) {
    return (void *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_com_saikai_ptt_audio_OpusNative_createEncoder(
        JNIEnv *env, jclass clazz,
        jint sampleRate, jint channels, jint application) {
    (void) env;
    (void) clazz;
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(sampleRate, channels, application, &error);
    if (error != OPUS_OK || encoder == NULL) {
        if (encoder != NULL) opus_encoder_destroy(encoder);
        return 0;
    }
    return to_handle(encoder);
}

JNIEXPORT jint JNICALL
Java_com_saikai_ptt_audio_OpusNative_configureEncoder(
        JNIEnv *env, jclass clazz, jlong handle,
        jint bitrate, jint complexity, jboolean useVariableBitrate,
        jboolean forwardErrorCorrection, jboolean discontinuous,
        jint expectedPacketLossPercent) {
    (void) env;
    (void) clazz;
    OpusEncoder *encoder = (OpusEncoder *) from_handle(handle);
    if (encoder == NULL) return OPUS_INVALID_STATE;

    int result;
    result = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    if (result != OPUS_OK) return result;
    result = opus_encoder_ctl(encoder, OPUS_SET_VBR(useVariableBitrate ? 1 : 0));
    if (result != OPUS_OK) return result;
    result = opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity));
    if (result != OPUS_OK) return result;
    result = opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(forwardErrorCorrection ? 1 : 0));
    if (result != OPUS_OK) return result;
    result = opus_encoder_ctl(encoder, OPUS_SET_DTX(discontinuous ? 1 : 0));
    if (result != OPUS_OK) return result;
    /*
     * Without this, in-band FEC is switched on and emits nothing at all:
     * libopus only spends bits on the redundant copy when it believes packets
     * are being lost. Enabling FEC alone -- which is all ADR-004 asked for --
     * would have paid nothing and bought nothing.
     */
    result = opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(expectedPacketLossPercent));
    if (result != OPUS_OK) return result;
    result = opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    return result;
}

JNIEXPORT jint JNICALL
Java_com_saikai_ptt_audio_OpusNative_encode(
        JNIEnv *env, jclass clazz, jlong handle,
        jshortArray pcm, jint pcmOffset, jint frameSamples,
        jbyteArray out, jint outOffset, jint outCapacity) {
    (void) clazz;
    OpusEncoder *encoder = (OpusEncoder *) from_handle(handle);
    if (encoder == NULL) return OPUS_INVALID_STATE;
    if (frameSamples <= 0 || frameSamples > SAIKAI_MAX_FRAME_SAMPLES) return OPUS_BAD_ARG;
    if (outCapacity <= 0) return OPUS_BAD_ARG;

    jsize pcmLength = (*env)->GetArrayLength(env, pcm);
    jsize outLength = (*env)->GetArrayLength(env, out);
    if (pcmOffset < 0 || pcmOffset + frameSamples > pcmLength) return OPUS_BAD_ARG;
    if (outOffset < 0 || outOffset + outCapacity > outLength) return OPUS_BAD_ARG;

    opus_int16 samples[SAIKAI_MAX_FRAME_SAMPLES];
    unsigned char packet[SAIKAI_MAX_PACKET];
    jint limit = outCapacity < SAIKAI_MAX_PACKET ? outCapacity : SAIKAI_MAX_PACKET;

    (*env)->GetShortArrayRegion(env, pcm, pcmOffset, frameSamples, samples);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return OPUS_BAD_ARG;
    }

    int written = opus_encode(encoder, samples, frameSamples, packet, limit);
    if (written < 0) return written;

    (*env)->SetByteArrayRegion(env, out, outOffset, written, (const jbyte *) packet);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return OPUS_BAD_ARG;
    }
    return written;
}

JNIEXPORT void JNICALL
Java_com_saikai_ptt_audio_OpusNative_destroyEncoder(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusEncoder *encoder = (OpusEncoder *) from_handle(handle);
    if (encoder != NULL) opus_encoder_destroy(encoder);
}

JNIEXPORT jlong JNICALL
Java_com_saikai_ptt_audio_OpusNative_createDecoder(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    (void) env;
    (void) clazz;
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    if (error != OPUS_OK || decoder == NULL) {
        if (decoder != NULL) opus_decoder_destroy(decoder);
        return 0;
    }
    return to_handle(decoder);
}

JNIEXPORT jint JNICALL
Java_com_saikai_ptt_audio_OpusNative_decode(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray encoded, jint encodedOffset, jint encodedLength,
        jshortArray pcm, jint pcmOffset, jint frameSamples,
        jboolean useForwardErrorCorrection) {
    (void) clazz;
    OpusDecoder *decoder = (OpusDecoder *) from_handle(handle);
    if (decoder == NULL) return OPUS_INVALID_STATE;
    if (frameSamples <= 0 || frameSamples > SAIKAI_MAX_FRAME_SAMPLES) return OPUS_BAD_ARG;
    if (encodedLength < 0 || encodedLength > SAIKAI_MAX_PACKET) return OPUS_BAD_ARG;

    jsize pcmLength = (*env)->GetArrayLength(env, pcm);
    if (pcmOffset < 0 || pcmOffset + frameSamples > pcmLength) return OPUS_BAD_ARG;

    unsigned char packet[SAIKAI_MAX_PACKET];
    opus_int16 samples[SAIKAI_MAX_FRAME_SAMPLES];

    if (encodedLength > 0) {
        jsize encodedArrayLength = (*env)->GetArrayLength(env, encoded);
        if (encodedOffset < 0 || encodedOffset + encodedLength > encodedArrayLength) {
            return OPUS_BAD_ARG;
        }
        (*env)->GetByteArrayRegion(env, encoded, encodedOffset, encodedLength, (jbyte *) packet);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            return OPUS_BAD_ARG;
        }
    }

    /*
     * A null packet with length zero is Opus's packet-loss concealment: it
     * produces a frame's worth of plausible audio rather than a hole. That is
     * strictly better than the silence ADR-004 section 4 settles for, and costs
     * the same call.
     */
    int produced = opus_decode(
            decoder,
            encodedLength > 0 ? packet : NULL,
            encodedLength,
            samples,
            frameSamples,
            useForwardErrorCorrection ? 1 : 0);
    if (produced < 0) return produced;

    (*env)->SetShortArrayRegion(env, pcm, pcmOffset, produced, samples);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return OPUS_BAD_ARG;
    }
    return produced;
}

JNIEXPORT void JNICALL
Java_com_saikai_ptt_audio_OpusNative_destroyDecoder(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoder *decoder = (OpusDecoder *) from_handle(handle);
    if (decoder != NULL) opus_decoder_destroy(decoder);
}
