#include "jpeg_encoder.h"
#include <jni.h>
#include <cstdint>
namespace {
void fail(JNIEnv* env) { if(!env->ExceptionCheck()) { jclass c=env->FindClass("java/io/IOException"); if(c) env->ThrowNew(c,"JPEG encoding failed"); } }
LoupeJpeg* encoder(jlong h) { return reinterpret_cast<LoupeJpeg*>(static_cast<intptr_t>(h)); }
}
extern "C" JNIEXPORT jlong JNICALL Java_jp_nano_rawgallery_NativeJpeg_openNative(JNIEnv* env,jclass,jint fd,jint w,jint h,jint quality) {
 LoupeJpeg* e=jpegOpen(fd,w,h,quality); if(!e) fail(env); return static_cast<jlong>(reinterpret_cast<intptr_t>(e));
}
extern "C" JNIEXPORT void JNICALL Java_jp_nano_rawgallery_NativeJpeg_writeNative(JNIEnv* env,jclass,jlong handle,jobject buffer,jint offset,jint bytes,jint rows) {
 auto* p=buffer ? static_cast<unsigned char*>(env->GetDirectBufferAddress(buffer)) : nullptr;
 jlong capacity=buffer ? env->GetDirectBufferCapacity(buffer) : -1;
 if(!handle || !p || offset<0 || bytes<0 || jlong(offset)+bytes>capacity || !jpegRows(encoder(handle),p+offset,size_t(bytes),rows)) fail(env);
}
extern "C" JNIEXPORT void JNICALL Java_jp_nano_rawgallery_NativeJpeg_finishNative(JNIEnv* env,jclass,jlong h) { if(!jpegFinish(encoder(h))) fail(env); }
extern "C" JNIEXPORT void JNICALL Java_jp_nano_rawgallery_NativeJpeg_closeNative(JNIEnv*,jclass,jlong h) { jpegClose(encoder(h)); }
