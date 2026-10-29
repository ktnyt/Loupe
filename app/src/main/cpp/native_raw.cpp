#include "raw_decode.h"
#include <jni.h>
#include <new>
#include <stdexcept>

namespace {
struct InterruptContext {
    JNIEnv* env;
    jobject thread;
    jmethodID isInterrupted;
};
int interrupted(void* opaque, LibRaw_progress, int, int) {
    auto* c = static_cast<InterruptContext*>(opaque);
    return c->env->CallBooleanMethod(c->thread, c->isInterrupted) || c->env->ExceptionCheck();
}
void fail(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) {
        jclass cls = env->FindClass(type);
        if (cls) env->ThrowNew(cls, message);
    }
}
class UtfPath {
public:
    UtfPath(JNIEnv* env, jstring path) : env_(env), path_(path), chars_(env->GetStringUTFChars(path, nullptr)) {}
    ~UtfPath() { if (chars_) env_->ReleaseStringUTFChars(path_, chars_); }
    const char* get() const { return chars_; }
private:
    JNIEnv* env_;
    jstring path_;
    const char* chars_;
};
}
extern "C" JNIEXPORT jobject JNICALL
Java_jp_nano_rawgallery_NativeRaw_decodeNative(JNIEnv* env, jclass, jstring path, jint maxSide) {
    if (!path || maxSide < 256 || maxSide > 2048) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid RAW decode request");
        return nullptr;
    }
    try {
        UtfPath file(env, path);
        if (!file.get()) return nullptr;
        jclass threadClass = env->FindClass("java/lang/Thread");
        if (!threadClass) return nullptr;
        jmethodID current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jmethodID isInterrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
        if (!current || !isInterrupted) return nullptr;
        jobject thread = env->CallStaticObjectMethod(threadClass, current);
        if (!thread || env->ExceptionCheck()) return nullptr;
        InterruptContext context{env, thread, isInterrupted};
        if (interrupted(&context, LIBRAW_PROGRESS_START, 0, 0)) {
            fail(env, "java/io/IOException", "RAW decoding cancelled");
            return nullptr;
        }
        loupe::RawImage raw(file.get(), maxSide, interrupted, &context);
        jclass bufferClass = env->FindClass("java/nio/ByteBuffer");
        if (!bufferClass) return nullptr;
        jmethodID allocate = env->GetStaticMethodID(bufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
        if (!allocate) return nullptr;
        const jint bytes = raw.width() * raw.height() * 4 * sizeof(float);
        jobject buffer = env->CallStaticObjectMethod(bufferClass, allocate, bytes);
        if (!buffer || env->ExceptionCheck()) return nullptr;
        auto* pixels = static_cast<float*>(env->GetDirectBufferAddress(buffer));
        if (!pixels || env->GetDirectBufferCapacity(buffer) != bytes) {
            fail(env, "java/io/IOException", "RAW pixel allocation failed");
            return nullptr;
        }
        raw.write(pixels);
        if (interrupted(&context, LIBRAW_PROGRESS_START, 0, 0)) {
            fail(env, "java/io/IOException", "RAW decoding cancelled");
            return nullptr;
        }
        jclass imageClass = env->FindClass("jp/nano/rawgallery/NativeRaw$Image");
        if (!imageClass) return nullptr;
        jmethodID ctor = env->GetMethodID(imageClass, "<init>", "(IIIILjava/nio/ByteBuffer;)V");
        return ctor ? env->NewObject(imageClass, ctor, raw.width(), raw.height(), raw.originalWidth(), raw.originalHeight(), buffer) : nullptr;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "RAW decoding memory limit exceeded");
    } catch (const std::invalid_argument&) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid RAW decode request");
    } catch (const std::exception&) {
        // Do not expose exception text from dependencies, paths, or metadata.
        fail(env, "java/io/IOException", "RAW decoding failed");
    } catch (...) {
        fail(env, "java/io/IOException", "RAW decoding failed");
    }
    return nullptr;
}

#include "raw_full.h"
namespace {
struct FullCancellation { JNIEnv* env; jobject cancellation; jmethodID method; };
int fullCancelled(void* opaque, LibRaw_progress, int, int) {
 auto* c=static_cast<FullCancellation*>(opaque);
 return c->env->CallBooleanMethod(c->cancellation,c->method) || c->env->ExceptionCheck();
}
}
extern "C" JNIEXPORT void JNICALL
Java_jp_nano_rawgallery_NativeRaw_prepareFullNative(JNIEnv* env,jclass,jstring input,jstring output,jobject cancellation) {
 if(!input || !output || !cancellation) { fail(env,"java/lang/IllegalArgumentException","Invalid RAW preparation request"); return; }
 try {
  UtfPath in(env,input), out(env,output); if(!in.get() || !out.get()) return;
  jclass cls=env->GetObjectClass(cancellation); if(!cls) return;
  jmethodID method=env->GetMethodID(cls,"isCancelled","()Z"); if(!method) return;
  FullCancellation c{env,cancellation,method};
  loupe::prepareFull(in.get(),out.get(),fullCancelled,&c);
 } catch(const std::bad_alloc&) { fail(env,"java/lang/OutOfMemoryError","RAW preparation memory limit exceeded"); }
 catch(...) { fail(env,"java/io/IOException","RAW preparation failed or cancelled"); }
}
