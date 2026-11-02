#!/usr/bin/env python3
"""Generate the built-in Java color lattice from the measured calibration model."""
import argparse
import hashlib
import json
import math
from pathlib import Path


def generate(source):
    model = json.loads(source.read_text())
    if model.get('format') != 'gallery-cnt-spatial-v3' or model.get('grid_size') != 7:
        raise ValueError('Expected the frozen 7^3 Gallery CNT model')
    data = model['control_lattice_rgb']
    if (len(data) != 7 or any(len(z) != 7 for z in data)
            or any(len(y) != 7 for z in data for y in z)
            or any(len(x) != 3 for z in data for y in z for x in y)):
        raise ValueError('Invalid RGB lattice dimensions')
    values = [float(v) for z in data for y in z for x in y for v in x]
    if any(not math.isfinite(v) or abs(v) > 2 for v in values):
        raise ValueError('Invalid calibrated RGB value')
    rows = [', '.join(f'{v:.10g}f' if '.' in f'{v:.10g}' or 'e' in f'{v:.10g}' else f'{v:.10g}.0f'
                      for v in values[i:i + 9]) for i in range(0, len(values), 9)]
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    return '''package jp.nano.rawgallery;

/** Measured SDR sRGB Gallery CNT color model; generated, not a camera/vendor LUT.
 * Regenerate with scripts/export-cnt-calibration.py and docs/calibration/gallery-cnt-v1.json.
 * Model SHA-256: %s
 * Red-fastest 7^3 control lattice. Preserve out-of-gamut values until final spatial/output clamp.
 */
final class RawEditCntCalibration {
  private RawEditCntCalibration() {}
  private static final float[] CONTROL = {
    %s
  };
  static double[] sample(double r,double g,double b) {
    double[] p={r*6,g*6,b*6},f=new double[3],out=new double[3];int[] lo=new int[3];
    for(int k=0;k<3;k++){lo[k]=Math.min(5,(int)Math.floor(p[k]));f[k]=p[k]-lo[k];}
    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){
      double w=(x==0?1-f[0]:f[0])*(y==0?1-f[1]:f[1])*(z==0?1-f[2]:f[2]);
      int i=(((lo[2]+z)*7+lo[1]+y)*7+lo[0]+x)*3;
      for(int k=0;k<3;k++)out[k]+=CONTROL[i+k]*w;
    }return out;
  }
}
''' % (digest, ',\n    '.join(rows))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    text = generate(args.model)
    if args.check:
        if args.output.read_text() != text:
            raise SystemExit('Generated calibration differs from model')
    else:
        with args.output.open('x') as stream:
            stream.write(text)


if __name__ == '__main__':
    main()
