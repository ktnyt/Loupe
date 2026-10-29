#pragma once
#include <cstddef>
struct LoupeJpeg;
LoupeJpeg* jpegOpen(int fd,int width,int height,int quality);
bool jpegRows(LoupeJpeg* encoder,const unsigned char* rgb,size_t bytes,int rows);
bool jpegFinish(LoupeJpeg* encoder);
void jpegClose(LoupeJpeg* encoder);
