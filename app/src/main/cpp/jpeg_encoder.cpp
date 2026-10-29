#include "jpeg_encoder.h"
#include <cstdio>
#include <cstdlib>
#include <new>

#include <cstdint>
#include <setjmp.h>
#include <unistd.h>
#include <fcntl.h>
#include <jpeglib.h>
struct LoupeJpeg {
 jpeg_compress_struct c{}; jpeg_error_mgr error{}; jmp_buf jump;
 FILE* file=nullptr; bool created=false,failed=false,finished=false; int width=0,height=0;
};
namespace {
void errorExit(j_common_ptr c) { auto* e=static_cast<LoupeJpeg*>(c->client_data); longjmp(e->jump,1); }
void quiet(j_common_ptr) {}
}
void jpegClose(LoupeJpeg* e) {
 if(!e) return;
 // Destroy does not invoke the output destination or run a compression pass.
 if(e->created) jpeg_destroy_compress(&e->c);
 if(e->file) fclose(e->file);
 delete e;
}
LoupeJpeg* jpegOpen(int fd,int width,int height,int quality) {
 if(fd<0 || width<=0 || height<=0 || width>16384 || height>16384 || uint64_t(width)*height>80000000 || quality<1 || quality>100) return nullptr;
 auto* e=new(std::nothrow) LoupeJpeg; if(!e) return nullptr;
 int owned=fcntl(fd,F_DUPFD_CLOEXEC,0); if(owned<0) { delete e; return nullptr; }
 e->file=fdopen(owned,"wb"); if(!e->file) { close(owned); delete e; return nullptr; }
 e->width=width; e->height=height;
 e->c.err=jpeg_std_error(&e->error); e->error.error_exit=errorExit; e->error.output_message=quiet; e->c.client_data=e;
 if(setjmp(e->jump)) { jpegClose(e); return nullptr; }
 e->created=true; jpeg_create_compress(&e->c); jpeg_stdio_dest(&e->c,e->file);
 e->c.image_width=width; e->c.image_height=height; e->c.input_components=3; e->c.in_color_space=JCS_RGB;
 jpeg_set_defaults(&e->c); jpeg_set_quality(&e->c,quality,TRUE); jpeg_start_compress(&e->c,TRUE);
 return e;
}
bool jpegRows(LoupeJpeg* e,const unsigned char* rgb,size_t bytes,int rows) {
 if(!e || e->failed || e->finished || !rgb || rows<=0 || rows>e->height-int(e->c.next_scanline) || uint64_t(rows)*e->width*3!=bytes) return false;
 if(setjmp(e->jump)) { e->failed=true; return false; }
 const size_t stride=size_t(e->width)*3;
 for(int r=0;r<rows;r++) { JSAMPROW row=const_cast<JSAMPROW>(rgb+size_t(r)*stride); if(jpeg_write_scanlines(&e->c,&row,1)!=1) { e->failed=true; return false; } }
 return true;
}
bool jpegFinish(LoupeJpeg* e) {
 if(!e || e->failed || e->finished || e->c.next_scanline!=unsigned(e->height)) return false;
 if(setjmp(e->jump)) { e->failed=true; return false; }
 jpeg_finish_compress(&e->c);
 bool ok=!ferror(e->file) && fflush(e->file)==0 && fsync(fileno(e->file))==0;
 int result=fclose(e->file); e->file=nullptr; e->finished=ok && result==0; e->failed=!e->finished;
 return e->finished;
}
