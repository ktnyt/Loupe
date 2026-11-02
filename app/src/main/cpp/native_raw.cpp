#include "raw_decode.h"
#include <jni.h>
#include <new>
#include <stdexcept>
#include <sys/stat.h>

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

extern "C" JNIEXPORT jintArray JNICALL
Java_jp_nano_rawgallery_NativeRaw_identifyNative(JNIEnv* env,jclass,jstring input) {
 if(!input){fail(env,"java/lang/IllegalArgumentException","Missing RAW input");return nullptr;}
 try {
  UtfPath path(env,input);if(!path.get())return nullptr;
  struct stat st{};
  if(stat(path.get(),&st)!=0||!S_ISREG(st.st_mode)||st.st_size<=0||uint64_t(st.st_size)>256ULL*1024*1024)
   throw std::runtime_error("Invalid RAW input");
  jclass threadClass=env->FindClass("java/lang/Thread");if(!threadClass)return nullptr;
  jmethodID current=env->GetStaticMethodID(threadClass,"currentThread","()Ljava/lang/Thread;");
  jmethodID isInterrupted=env->GetMethodID(threadClass,"isInterrupted","()Z");
  if(!current||!isInterrupted)return nullptr;
  jobject thread=env->CallStaticObjectMethod(threadClass,current);if(!thread||env->ExceptionCheck())return nullptr;
  InterruptContext cancellation{env,thread,isInterrupted};
  if(interrupted(&cancellation,LIBRAW_PROGRESS_START,0,0))throw std::runtime_error("Cancelled RAW identification");
  LibRaw raw;raw.set_dataerror_handler([](void*,const char*,INT64){},nullptr);
  raw.set_progress_handler(interrupted,&cancellation);
  raw.imgdata.rawparams.max_raw_memory_mb=256;
  auto& p=raw.imgdata.params;p.half_size=0;p.output_color=1;p.output_bps=16;
  if(raw.open_file(path.get())!=LIBRAW_SUCCESS)throw std::runtime_error("RAW identification failed");
  auto valid=[](int w,int h){return w>0&&h>0&&w<=16384&&h<=16384&&uint64_t(w)*h<=80000000;};
  const auto& z=raw.imgdata.sizes;
  if(!valid(z.raw_width,z.raw_height)||!valid(z.width,z.height))throw std::runtime_error("Invalid RAW dimensions");
  int w,h,c,b;raw.get_mem_image_format(&w,&h,&c,&b);
  if(!valid(w,h))throw std::runtime_error("Invalid RAW output dimensions");
  jint values[]={w,h};jintArray result=env->NewIntArray(2);
  if(result)env->SetIntArrayRegion(result,0,2,values);return result;
 }catch(const std::bad_alloc&){fail(env,"java/lang/OutOfMemoryError","RAW identification memory limit exceeded");}
 catch(...){fail(env,"java/io/IOException","RAW identification failed");}
 return nullptr;
}
