#include "raw_full.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
namespace {
void bad() { throw std::runtime_error("RAW preparation failed"); }
void check(int code) { if (code != LIBRAW_SUCCESS) bad(); }
void dimensions(int w,int h) { if(w<=0 || h<=0 || w>16384 || h>16384 || uint64_t(w)*h>80000000) bad(); }
void silence(void*, const char*, const INT64) {}
// Keep the upstream scanline writer unchanged, while owning/checking its FILE lifecycle.
class Writer : public LibRaw {
public:
 void writeChecked(FILE* f) {
  if (!libraw_internal_data.output_data.histogram) bad();
  libraw_internal_data.internal_data.output=f;
  try { write_ppm_tiff(); } catch (...) { libraw_internal_data.internal_data.output=nullptr; throw; }
  libraw_internal_data.internal_data.output=nullptr;
 }
};
}
namespace loupe {
void validateFullCache(const char* path,int width,int height,int channels) {
 dimensions(width,height);
 if(channels!=1 && channels!=3) bad();
 FILE* f=fopen(path,"rb"); if(!f) bad();
 // The unmodified writer, with metadata disabled, emits exactly this canonical header.
 char expected[96]; int n=snprintf(expected,sizeof(expected),"P%d\n%d %d\n65535\n",channels==1?5:6,width,height);
 char actual[96]{}; bool ok=n>0 && fread(actual,1,n,f)==size_t(n) && !memcmp(actual,expected,n);
 struct stat st{}; ok=ok && fstat(fileno(f),&st)==0 && S_ISREG(st.st_mode) && uint64_t(st.st_size)==uint64_t(n)+uint64_t(width)*height*channels*2;
 if(fclose(f)!=0) ok=false;
 if(!ok) bad();
}
void prepareFull(const char* input,const char* output,progress_callback callback,void* context) {
 if(!input || !output) bad();
 auto cancelled=[&] { if(callback && callback(context,LIBRAW_PROGRESS_START,0,0)) bad(); };
 cancelled();
 struct stat st{}; if(stat(input,&st)!=0 || !S_ISREG(st.st_mode) || st.st_size<=0 || uint64_t(st.st_size)>256ULL*1024*1024) bad();
 // Exclusive creation protects existing files, including the input, from truncation.
 int fd=open(output,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600); if(fd<0) bad();
 FILE* f=fdopen(fd,"wb"); if(!f) { close(fd); unlink(output); bad(); }
 bool complete=false;
 try {
  {
   Writer raw; raw.set_dataerror_handler(silence,nullptr); if(callback) raw.set_progress_handler(callback,context);
   raw.imgdata.rawparams.max_raw_memory_mb=256;
   auto& p=raw.imgdata.params; p.half_size=0; p.use_camera_wb=1; p.use_auto_wb=0; p.no_auto_bright=1;
   p.gamm[0]=p.gamm[1]=1.; p.output_color=1; p.output_bps=16; p.user_qual=0; p.output_tiff=0; p.output_flags=0;
   check(raw.open_file(input)); dimensions(raw.imgdata.sizes.raw_width,raw.imgdata.sizes.raw_height); dimensions(raw.imgdata.sizes.width,raw.imgdata.sizes.height);
   cancelled(); check(raw.unpack()); if(raw.error_count()) bad(); cancelled(); check(raw.dcraw_process()); if(raw.error_count()) bad();
   int w,h,c,b; raw.get_mem_image_format(&w,&h,&c,&b); dimensions(w,h); if((c!=1 && c!=3)||b!=16) bad();
   cancelled(); raw.writeChecked(f); // Upstream writer and linear interpolation have cooperative-cancellation gaps.
   bool ok=!ferror(f) && fflush(f)==0 && fsync(fileno(f))==0;
   int result=fclose(f); f=nullptr; if(!ok || result!=0) bad();
   cancelled(); validateFullCache(output,w,h,c); cancelled();
  } // Release LibRaw buffers before returning a completed cache.
  cancelled(); complete=true;
 } catch(...) { if(f) fclose(f); unlink(output); throw; }
 if(!complete) unlink(output);
}
}
