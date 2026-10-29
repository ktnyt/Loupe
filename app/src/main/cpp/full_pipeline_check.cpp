#include "raw_full.h"
#include "raw_decode.h"
#include "jpeg_encoder.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <jpeglib.h>
namespace {
std::string temporary() { char name[]="/tmp/loupe-native-check-XXXXXX"; int fd=mkstemp(name); assert(fd>=0); close(fd); return name; }
void cacheChecks() {
 for(int c: {1,3}) {
  auto path=temporary(); FILE* f=fopen(path.c_str(),"wb"); fprintf(f,"P%d\n3 2\n65535\n",c==1?5:6); for(int i=0;i<3*2*c*2;i++) fputc(i,f); fclose(f);
  loupe::validateFullCache(path.c_str(),3,2,c);
  bool rejected=false; try { loupe::validateFullCache(path.c_str(),2,3,c); } catch(...) { rejected=true; } assert(rejected);
  f=fopen(path.c_str(),"ab"); fputc(0,f); fclose(f); rejected=false;
  try { loupe::validateFullCache(path.c_str(),3,2,c); } catch(...) { rejected=true; } assert(rejected); unlink(path.c_str());
 }
 auto input=temporary(), out=temporary(); bool rejected=false;
 try { loupe::prepareFull(input.c_str(),out.c_str(),nullptr,nullptr); } catch(...) { rejected=true; }
 assert(rejected && access(out.c_str(),F_OK)==0); // Never delete an existing output.
 auto cancel=+[](void*,LibRaw_progress,int,int)->int{return 1;};
 unlink(out.c_str()); rejected=false;
 try { loupe::prepareFull(input.c_str(),out.c_str(),cancel,nullptr); } catch(...) { rejected=true; }
 assert(rejected && access(out.c_str(),F_OK)!=0);
 FILE* f=fopen(input.c_str(),"wb"); fputs("invalid RAW",f); fclose(f); rejected=false;
 try { loupe::prepareFull(input.c_str(),out.c_str(),nullptr,nullptr); } catch(...) { rejected=true; }
 assert(rejected && access(out.c_str(),F_OK)!=0); unlink(input.c_str());
}
void jpegChecks() {
 auto path=temporary(); int fd=open(path.c_str(),O_RDWR|O_TRUNC); assert(fd>=0);
 assert(!jpegOpen(fd,0,3,90)); assert(!jpegOpen(fd,16385,3,90)); assert(!jpegOpen(fd,3,3,101));
 LoupeJpeg* e=jpegOpen(fd,7,5,90); assert(e); close(fd); // Encoder owns its duplicate.
 std::vector<unsigned char> rgb(7*3*5); for(size_t i=0;i<rgb.size();i++) rgb[i]=(i*71)%256;
 assert(!jpegRows(e,rgb.data(),7*3*2-1,2)); assert(!jpegRows(e,rgb.data(),0,0));
 assert(jpegRows(e,rgb.data(),7*3*2,2)); assert(!jpegFinish(e));
 assert(!jpegRows(e,rgb.data(),7*3*4,4));
 assert(jpegRows(e,rgb.data()+7*3*2,7*3*3,3)); assert(jpegFinish(e)); assert(!jpegFinish(e)); jpegClose(e);
 FILE* f=fopen(path.c_str(),"rb"); jpeg_decompress_struct d{}; jpeg_error_mgr err{}; d.err=jpeg_std_error(&err); jpeg_create_decompress(&d); jpeg_stdio_src(&d,f); assert(jpeg_read_header(&d,TRUE)==JPEG_HEADER_OK); assert(d.image_width==7 && d.image_height==5); jpeg_destroy_decompress(&d); fclose(f);
 fd=open(path.c_str(),O_WRONLY|O_TRUNC); e=jpegOpen(fd,7,5,90); assert(e); jpegClose(e); assert(fcntl(fd,F_GETFD)>=0); close(fd); unlink(path.c_str()); // Abort preserves caller fd.
 fd=open("/dev/null",O_RDONLY); assert(fd>=0); e=jpegOpen(fd,7,5,90);
 if(e) { bool wrote=jpegRows(e,rgb.data(),rgb.size(),5); assert(!wrote || !jpegFinish(e)); jpegClose(e); }
 assert(fcntl(fd,F_GETFD)>=0); close(fd);
 assert(!jpegOpen(-1,7,5,90));
}
}
int main(int argc,char** argv) {
 cacheChecks(); jpegChecks();
 if(argc==3) {
  int originalWidth,originalHeight;
  {
   loupe::RawImage preview(argv[1],2048);
   originalWidth=preview.originalWidth();originalHeight=preview.originalHeight();
   assert(preview.width()<=2048 && preview.height()<=2048);
  }
  loupe::prepareFull(argv[1],argv[2],nullptr,nullptr);
  {
   FILE* cache=fopen(argv[2],"rb");assert(cache);int magic,w,h,maxval;
   assert(fscanf(cache,"P%d %d %d %d",&magic,&w,&h,&maxval)==4);fclose(cache);
   assert(originalWidth==w && originalHeight==h);
   printf("preview metadata equals prepared effective dimensions: %dx%d\n",w,h);
  }
  std::string output=std::string(argv[2])+"-cancel"; int calls=0;
  auto cancel=+[](void* p,LibRaw_progress,int,int)->int { return ++*static_cast<int*>(p)>=4; };
  bool rejected=false;
  try { loupe::prepareFull(argv[1],output.c_str(),cancel,&calls); } catch(...) { rejected=true; }
  assert(rejected && access(output.c_str(),F_OK)!=0);
 }
 puts("cache integrity/cancellation/cleanup and JPEG strips/dimensions/bounds/fd lifetime passed");
}
