#!/usr/bin/env python3
"""Create public synthetic PNG ramps/patches and an sRGB identity .cube for local look checks."""
import argparse
import binascii
import pathlib
import struct
import zlib


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=pathlib.Path)
    parser.add_argument('--size', type=int, choices=(17, 33, 65), default=17)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    # Exclusive creation preserves previous fixtures and their comparison baseline.
    width, height = 2048, 512
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            v = round(x * 255 / (width - 1))
            if y < 128:
                rgb = (v, v, v)
            elif y < 256:
                rgb = (v, round(v * .72), round(v * .52))
            elif y < 384:
                rgb = ((v, 0, 0), (0, v, 0), (0, 0, v))[min(2, x * 3 // width)]
            else:
                rgb = ((0, 0, 0), (8, 8, 8), (32, 32, 32), (128, 128, 128),
                       (224, 224, 224), (247, 247, 247), (255, 255, 255), (190, 140, 110))[min(7, x * 8 // width)]
            rows.extend(rgb)
    data = (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'sRGB', b'\0')
            + chunk(b'IDAT', zlib.compress(bytes(rows))) + chunk(b'IEND', b''))
    with (args.output / 'look-ramp-patches.png').open('xb') as stream:
        stream.write(data)
    with (args.output / f'identity-{args.size}.cube').open('x', encoding='ascii') as stream:
        stream.write(f'TITLE "Identity encoded sRGB"\nLUT_3D_SIZE {args.size}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n')
        for blue in range(args.size):
            for green in range(args.size):
                for red in range(args.size):
                    stream.write(f'{red/(args.size-1):.9f} {green/(args.size-1):.9f} {blue/(args.size-1):.9f}\n')
    print(f'Created synthetic sRGB patches and {args.size}^3 identity LUT in {args.output}')


if __name__ == '__main__':
    main()
