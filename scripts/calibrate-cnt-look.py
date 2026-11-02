#!/usr/bin/env python3
"""Build a reproducible sRGB chart and fit a measured 3D display-look LUT.

This is an offline calibration aid for Gallery's CNT-like look. It does not
modify RAW/base rendering, contain a camera LUT, or add grain.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import pathlib
import struct
import sys
from collections import defaultdict

import numpy as np
from PIL import Image, ImageCms, ImageOps
from scipy.optimize import least_squares
from scipy.sparse import coo_matrix, eye, vstack
from scipy.sparse.linalg import lsqr


PATCH_SIZE = 32
GUTTER = 2
INTERIOR_INSET = 6
REPEATED_COLOR_COUNT = 32
REPEAT_COUNT = 4
GRAYSCALE_COUNT = 256
TRAIN_GRID_SIZE = 17
VALIDATION_GRID_SIZE = 8
LUT_SIZE = 33
FIT_SIZES = (5, 7)
CHART_COLUMNS = 64
IDENTITY_PENALTY = 0.03
SMOOTHNESS_PENALTY = 1.5
MEAN_DELTA_E_MEANINGFUL = 0.15


def srgb_profile_bytes() -> bytes:
    profile = bytearray(ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())
    # LittleCMS writes the current timestamp into the ICC header. Canonicalize
    # it so identical seeds produce byte-identical chart files across runs.
    profile[24:36] = struct.pack(">6H", 2020, 1, 1, 0, 0, 0)
    return bytes(profile)


def ensure_new_directory(path: pathlib.Path) -> None:
    path = path.expanduser()
    if path.exists():
        raise FileExistsError(f"output directory already exists: {path}")
    path.mkdir(parents=True)


def make_samples(seed: int) -> list[dict]:
    samples: list[dict] = []
    for b in range(TRAIN_GRID_SIZE):
        for g in range(TRAIN_GRID_SIZE):
            for r in range(TRAIN_GRID_SIZE):
                samples.append({
                    "kind": "train",
                    "rgb": [r / 16.0, g / 16.0, b / 16.0],
                    "key": f"train-{r:02d}-{g:02d}-{b:02d}",
                })

    # Sample every other training-cell center, halfway between the 17^3
    # lattice nodes. This keeps an 8^3 held-out set disjoint from training.
    for b in range(VALIDATION_GRID_SIZE):
        for g in range(VALIDATION_GRID_SIZE):
            for r in range(VALIDATION_GRID_SIZE):
                samples.append({
                    "kind": "validation",
                    "rgb": [(2 * r + 0.5) / 16, (2 * g + 0.5) / 16, (2 * b + 0.5) / 16],
                    "key": f"validation-{r:02d}-{g:02d}-{b:02d}",
                })

    for i in range(GRAYSCALE_COUNT):
        samples.append({
            "kind": "grayscale",
            "rgb": [i / 255.0] * 3,
            "key": f"gray-{i:03d}",
            "level": i,
        })

    rng = np.random.default_rng(seed)
    lattice_points = np.array([(r, g, b) for b in range(TRAIN_GRID_SIZE)
                               for g in range(TRAIN_GRID_SIZE)
                               for r in range(TRAIN_GRID_SIZE)])
    repeat_rng = np.random.default_rng(0xC017)
    repeat_rgb = lattice_points[repeat_rng.choice(len(lattice_points), size=REPEATED_COLOR_COUNT, replace=False)] / 16.0
    for group, rgb in enumerate(repeat_rgb):
        for repeat in range(REPEAT_COUNT):
            samples.append({
                "kind": "repeat",
                "rgb": rgb.tolist(),
                "key": f"repeat-{group:02d}-{repeat}",
                "repeat_group": f"repeat-{group:02d}",
            })

    rng.shuffle(samples)
    return samples


def render_chart(samples: list[dict], output: pathlib.Path, seed: int) -> tuple[int, int]:
    columns = CHART_COLUMNS
    rows = math.ceil(len(samples) / columns)
    cell = PATCH_SIZE + GUTTER
    width = columns * cell + GUTTER
    height = rows * cell + GUTTER
    pixels = np.full((height, width, 3), 128, dtype=np.uint8)
    for index, sample in enumerate(samples):
        row, column = divmod(index, columns)
        x = GUTTER + column * cell
        y = GUTTER + row * cell
        rgb8 = np.rint(np.asarray(sample["rgb"]) * 255).astype(np.uint8)
        pixels[y:y + PATCH_SIZE, x:x + PATCH_SIZE] = rgb8
        sample["rect"] = [x, y, PATCH_SIZE, PATCH_SIZE]
        sample["index"] = index

    image = Image.fromarray(pixels, "RGB")
    image.save(
        output,
        format="JPEG",
        quality=100,
        subsampling=0,
        optimize=False,
        progressive=False,
        icc_profile=srgb_profile_bytes(),
    )
    return width, height


def command_generate(args: argparse.Namespace) -> int:
    output = args.output.expanduser()
    ensure_new_directory(output)
    samples = make_samples(args.seed)
    chart_path = output / "chart.jpg"
    width, height = render_chart(samples, chart_path, args.seed)
    manifest = {
        "format": "loupe-cnt-look-chart-v1",
        "purpose": "Gallery CNT-like look calibration; no raw-base modification",
        "seed": args.seed,
        "chart": "chart.jpg",
        "width": width,
        "height": height,
        "orientation": 1,
        "profile": "sRGB ICC",
        "jpeg": {"quality": 100, "subsampling": 0},
        "patch_size": PATCH_SIZE,
        "gutter": GUTTER,
        "interior_inset": INTERIOR_INSET,
        "training": {"rgb_lattice": [17, 17, 17], "patch_count": 17**3, "samples": "train and grayscale only"},
        "validation": {"off_grid_lattice": [8, 8, 8], "patch_count": 8**3, "samples": "cell centers, disjoint from training lattice"},
        "context_repeats": {"color_count": REPEATED_COLOR_COUNT, "repeats_per_color": REPEAT_COUNT, "samples": "report only, excluded from fitting"},
        "samples": samples,
    }
    with (output / "manifest.json").open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(f"Generated {chart_path} ({width}x{height}); manifest: {output / 'manifest.json'}")
    print("Neutral/Before workflow: import this JPEG, export with neutral settings, and export the intended same-input result.")
    return 0


def load_srgb_image(path: pathlib.Path) -> tuple[np.ndarray, dict]:
    with Image.open(path) as source:
        orientation = source.getexif().get(274, 1)
        if orientation != 1:
            raise ValueError(f"{path}: EXIF orientation {orientation} is not upright (1)")
        icc = source.info.get("icc_profile")
        if not icc:
            raise ValueError(f"{path}: embedded ICC profile is required")
        if source.mode not in ("RGB", "RGBA"):
            raise ValueError(f"{path}: expected RGB image, got mode {source.mode}")
        if source.mode == "RGBA":
            alpha = np.asarray(source.getchannel("A"))
            if np.any(alpha != 255):
                raise ValueError(f"{path}: transparency is not supported")
            source = source.convert("RGB")
        source_profile = ImageCms.ImageCmsProfile(io_bytes(icc))
        target_profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))
        converted = ImageCms.profileToProfile(source, source_profile, target_profile, outputMode="RGB")
        array = np.asarray(converted, dtype=np.float64) / 255.0
        if not np.isfinite(array).all():
            raise ValueError(f"{path}: image contains non-finite values")
        return array, {"size": list(converted.size), "orientation": orientation, "icc_bytes": len(icc), "mode": source.mode}


def io_bytes(data: bytes):
    import io
    return io.BytesIO(data)


def srgb_to_linear(rgb: np.ndarray) -> np.ndarray:
    rgb = np.asarray(rgb, dtype=np.float64)
    decoded = np.empty_like(rgb)
    low = rgb <= 0.04045
    decoded[low] = rgb[low] / 12.92
    decoded[~low] = ((rgb[~low] + 0.055) / 1.055) ** 2.4
    return decoded


def rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    rgb = np.asarray(rgb, dtype=np.float64)
    linear = srgb_to_linear(rgb)
    xyz = linear @ np.array([
        [0.4124564, 0.2126729, 0.0193339],
        [0.3575761, 0.7151522, 0.1191920],
        [0.1804375, 0.0721750, 0.9503041],
    ])
    xyz /= np.array([0.95047, 1.0, 1.08883])
    delta = 6 / 29
    f = np.where(xyz > delta**3, np.cbrt(xyz), xyz / (3 * delta**2) + 4 / 29)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)


def delta_e76(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return np.linalg.norm(rgb_to_lab(a) - rgb_to_lab(b), axis=-1)


def patch_median(image: np.ndarray, rect: list[int], inset: int) -> np.ndarray:
    x, y, width, height = rect
    if width <= inset * 2 or height <= inset * 2:
        raise ValueError("patch interior is empty")
    region = image[y + inset:y + height - inset, x + inset:x + width - inset]
    result = np.median(region.reshape(-1, 3), axis=0)
    if not np.isfinite(result).all():
        raise ValueError("patch measurement contains non-finite values")
    return result


def validate_manifest(manifest: dict) -> None:
    if not isinstance(manifest, dict) or manifest.get("format") != "loupe-cnt-look-chart-v1":
        raise ValueError("unsupported chart manifest")
    width, height = manifest.get("width"), manifest.get("height")
    if not isinstance(width, int) or not isinstance(height, int) or width < 1 or height < 1:
        raise ValueError("manifest dimensions must be positive integers")
    if manifest.get("orientation") != 1:
        raise ValueError("manifest orientation must be upright (1)")
    patch_size, gutter, inset = (manifest.get(key) for key in ("patch_size", "gutter", "interior_inset"))
    if not all(isinstance(value, int) for value in (patch_size, gutter, inset)):
        raise ValueError("manifest patch geometry must use integer values")
    if patch_size < 2 or gutter < 0 or inset < 0 or inset * 2 >= patch_size:
        raise ValueError("manifest patch geometry is invalid")
    samples = manifest.get("samples")
    expected_count = TRAIN_GRID_SIZE**3 + VALIDATION_GRID_SIZE**3 + GRAYSCALE_COUNT + REPEATED_COLOR_COUNT * REPEAT_COUNT
    if not isinstance(samples, list) or len(samples) != expected_count:
        raise ValueError(f"manifest must contain exactly {expected_count} samples")
    expected_width = CHART_COLUMNS * (patch_size + gutter) + gutter
    expected_height = math.ceil(expected_count / CHART_COLUMNS) * (patch_size + gutter) + gutter
    if (width, height) != (expected_width, expected_height) or width * height > 20_000_000:
        raise ValueError("manifest chart dimensions do not match the bounded chart layout")
    valid_kinds = {"train", "validation", "grayscale", "repeat"}
    counts = defaultdict(int)
    seen_indices, seen_keys, seen_rects = set(), set(), set()
    intended_colors = {"train": set(), "validation": set()}
    by_index = [None] * len(samples)
    cell = patch_size + gutter
    for sample in samples:
        if not isinstance(sample, dict) or sample.get("kind") not in valid_kinds:
            raise ValueError("manifest contains an invalid sample")
        kind = sample["kind"]
        counts[kind] += 1
        index, key, rgb, rect = sample.get("index"), sample.get("key"), sample.get("rgb"), sample.get("rect")
        if not isinstance(index, int) or index < 0 or index >= len(samples) or index in seen_indices:
            raise ValueError("manifest sample indices must be unique and contiguous")
        if not isinstance(key, str) or not key or key in seen_keys:
            raise ValueError("manifest sample keys must be unique non-empty strings")
        if not isinstance(rgb, list) or len(rgb) != 3:
            raise ValueError("manifest sample RGB must contain three values")
        color = np.asarray(rgb, dtype=np.float64)
        if not np.isfinite(color).all() or np.any(color < 0) or np.any(color > 1):
            raise ValueError("manifest sample RGB values must be finite and in [0,1]")
        if kind in intended_colors:
            intended_colors[kind].add(tuple(color.tolist()))
        if not isinstance(rect, list) or len(rect) != 4 or not all(isinstance(v, int) for v in rect):
            raise ValueError("manifest sample rectangle must contain four integers")
        x, y, rect_width, rect_height = rect
        if rect_width != patch_size or rect_height != patch_size or x < gutter or y < gutter:
            raise ValueError("manifest sample rectangle has invalid dimensions or origin")
        if (x - gutter) % cell or (y - gutter) % cell or x + rect_width > width or y + rect_height > height:
            raise ValueError("manifest sample rectangle is outside chart cells")
        cell_position = ((y - gutter) // cell, (x - gutter) // cell)
        if cell_position in seen_rects:
            raise ValueError("manifest contains overlapping or duplicate patch rectangles")
        seen_indices.add(index)
        seen_keys.add(key)
        seen_rects.add(cell_position)
        by_index[index] = sample
    expected_counts = {"train": TRAIN_GRID_SIZE**3, "validation": VALIDATION_GRID_SIZE**3,
                       "grayscale": GRAYSCALE_COUNT, "repeat": REPEATED_COLOR_COUNT * REPEAT_COUNT}
    if dict(counts) != expected_counts:
        raise ValueError(f"manifest sample counts are invalid: {dict(counts)}")
    if not intended_colors["train"].isdisjoint(intended_colors["validation"]):
        raise ValueError("manifest training and validation colors overlap")
    if any(sample is None for sample in by_index):
        raise ValueError("manifest sample indices are not contiguous")


def interpolation_matrix(inputs: np.ndarray, grid_size: int):
    rows, cols, values = [], [], []
    scaled = np.clip(inputs, 0.0, 1.0) * (grid_size - 1)
    lower = np.floor(scaled).astype(int)
    fraction = scaled - lower
    lower = np.minimum(lower, grid_size - 2)
    fraction = np.where(scaled >= grid_size - 1, 1.0, fraction)
    for mask in range(8):
        bits = np.array([(mask >> channel) & 1 for channel in range(3)])
        coordinate = lower + bits
        weights = np.prod(np.where(bits, fraction, 1 - fraction), axis=1)
        flat = (coordinate[:, 2] * grid_size + coordinate[:, 1]) * grid_size + coordinate[:, 0]
        rows.extend(range(len(inputs)))
        cols.extend(flat.tolist())
        values.extend(weights.tolist())
    return coo_matrix((values, (rows, cols)), shape=(len(inputs), grid_size**3)).tocsr()


def regularization_matrix(grid_size: int):
    count = grid_size**3
    rows, cols, vals = [], [], []
    row = 0
    for z in range(grid_size):
        for y in range(grid_size):
            for x in range(grid_size):
                for axis in range(3):
                    coords = [x, y, z]
                    if coords[axis] >= grid_size - 2:
                        continue
                    indices = []
                    for offset in range(3):
                        c = coords.copy()
                        c[axis] += offset
                        indices.append((c[2] * grid_size + c[1]) * grid_size + c[0])
                    for col, value in zip(indices, (1.0, -2.0, 1.0)):
                        rows.append(row)
                        cols.append(col)
                        vals.append(value)
                    row += 1
    second = coo_matrix((vals, (rows, cols)), shape=(row, count)).tocsr()
    return second


def fit_lattice(inputs: np.ndarray, targets: np.ndarray, grid_size: int) -> np.ndarray:
    design = interpolation_matrix(inputs, grid_size)
    count = grid_size**3
    coordinates = np.array([(r, g, b) for b in range(grid_size) for g in range(grid_size) for r in range(grid_size)], dtype=np.float64) / (grid_size - 1)
    smooth = regularization_matrix(grid_size)
    blocks = [design, math.sqrt(IDENTITY_PENALTY) * eye(count, format="csr"), math.sqrt(SMOOTHNESS_PENALTY) * smooth]
    system = vstack(blocks, format="csr")
    rhs = np.vstack([targets, math.sqrt(IDENTITY_PENALTY) * coordinates, np.zeros((smooth.shape[0], 3))])
    control = np.empty((count, 3), dtype=np.float64)
    for channel in range(3):
        solution = lsqr(system, rhs[:, channel], atol=1e-9, btol=1e-9, iter_lim=5000)
        control[:, channel] = solution[0]
        if solution[1] not in (1, 2):
            raise RuntimeError(f"LUT fit did not converge (grid {grid_size}, channel {channel}, status {solution[1]})")
    return control.reshape((grid_size, grid_size, grid_size, 3))


def evaluate_lattice(control: np.ndarray, inputs: np.ndarray) -> np.ndarray:
    size = control.shape[0]
    scaled = np.clip(inputs, 0.0, 1.0) * (size - 1)
    lo = np.floor(scaled).astype(int)
    fraction = scaled - lo
    lo = np.minimum(lo, size - 2)
    fraction = np.where(scaled >= size - 1, 1.0, fraction)
    output = np.zeros((len(inputs), 3), dtype=np.float64)
    for mask in range(8):
        bits = np.array([(mask >> channel) & 1 for channel in range(3)])
        coordinate = lo + bits
        weight = np.prod(np.where(bits, fraction, 1 - fraction), axis=1)
        output += control[coordinate[:, 2], coordinate[:, 1], coordinate[:, 0]] * weight[:, None]
    return output


def bake_spatial_cube(control: np.ndarray, size: int = LUT_SIZE) -> tuple[np.ndarray, dict]:
    """Bake the unclipped, nine-decimal encoded-sRGB cube used by spatial v3."""
    grid = np.linspace(0.0, 1.0, size)
    inputs = np.asarray([(r, g, b) for b in grid for g in grid for r in grid], dtype=np.float64)
    raw = evaluate_lattice(control, inputs)
    baked = np.round(raw, 9)
    return baked.reshape((size, size, size, 3)), {
        "size": size,
        "red_fastest": True,
        "decimal_places": 9,
        "unclipped_min": float(raw.min()),
        "unclipped_max": float(raw.max()),
        "out_of_range_channel_count": int(np.count_nonzero((raw < 0) | (raw > 1))),
        "out_of_range_channel_fraction": float(np.mean((raw < 0) | (raw > 1))),
    }


def validate_model(model: dict) -> np.ndarray:
    if not isinstance(model, dict):
        raise ValueError("model must be a JSON object")
    fmt = model.get("format")
    if fmt not in ("loupe-gallery-cnt-calibrated-model-v1", "gallery-cnt-spatial-v3"):
        raise ValueError("unsupported Gallery CNT model format")
    grid_size = model.get("grid_size")
    if fmt == "gallery-cnt-spatial-v3" and grid_size != 7:
        raise ValueError("spatial v3 requires a 7^3 control lattice")
    if fmt == "loupe-gallery-cnt-calibrated-model-v1" and grid_size not in (5, 7):
        raise ValueError("global model grid size must be 5 or 7")
    if model.get("input_domain") not in ("encoded SDR sRGB", "encoded SDR sRGB [0,1]"):
        raise ValueError("model input domain must be encoded SDR sRGB")
    if fmt == "gallery-cnt-spatial-v3":
        if model.get("spatial_domain") != "encoded" or model.get("spatial_family") != "plateau_power_highlight":
            raise ValueError("spatial v3 requires encoded plateau_power_highlight")
        if model.get("brightness") not in ("luma", "maxRGB"):
            raise ValueError("spatial brightness proxy must be luma or maxRGB")
    control = np.asarray(model.get("control_lattice_rgb"), dtype=np.float64)
    expected_shape = (grid_size, grid_size, grid_size, 3)
    if control.shape != expected_shape or not np.isfinite(control).all():
        raise ValueError(f"model control lattice must be finite with shape {expected_shape}")
    if fmt == "gallery-cnt-spatial-v3":
        params = [model.get(key) for key in ("strength", "start_radius_squared", "power", "highlight_low", "highlight_high")]
        params_array = np.asarray(params, dtype=np.float64)
        if not np.isfinite(params_array).all():
            raise ValueError("spatial parameters must be finite")
        strength, start, power, low, high = params_array
        if not (0 <= strength <= 0.9 and 0 <= start <= 0.65 and 0.3 <= power <= 4):
            raise ValueError("spatial strength/radius/power are outside supported bounds")
        if not (0 <= low <= 0.85 and 0.86 <= high <= 1.3 and high > low):
            raise ValueError("spatial highlight thresholds are outside supported bounds")
    return control


def apply_model(model: dict, rgb: np.ndarray, uv: np.ndarray, *, spatial_enabled: bool = True, lut: np.ndarray | None = None) -> np.ndarray:
    control = lut if lut is not None else validate_model(model)
    source = np.asarray(rgb, dtype=np.float64)
    coordinates = np.asarray(uv, dtype=np.float64)
    original_shape = source.shape
    if source.shape[-1] != 3 or coordinates.shape != source.shape[:-1] + (2,):
        raise ValueError("RGB and normalized UV arrays must have matching shapes ending in 3 and 2")
    flat = source.reshape((-1, 3))
    output = evaluate_lattice(control, flat).reshape(original_shape)
    if model.get("format") != "gallery-cnt-spatial-v3" or not spatial_enabled or model["strength"] == 0:
        return np.clip(output, 0.0, 1.0)

    radial_squared = 2.0 * np.sum((coordinates - 0.5) ** 2, axis=-1)
    bounded = np.clip(output, 0.0, 1.0)
    if model["brightness"] == "maxRGB":
        brightness = bounded.max(axis=-1)
    else:
        brightness = bounded @ np.array([0.2126, 0.7152, 0.0722])
    t = np.clip((radial_squared - model["start_radius_squared"]) / (1.0 - model["start_radius_squared"]), 0.0, 1.0)
    highlight = np.clip((brightness - model["highlight_low"]) / (model["highlight_high"] - model["highlight_low"]), 0.0, 1.0)
    protection = highlight * highlight * (3.0 - 2.0 * highlight)
    gain = 1.0 - model["strength"] * np.power(t, model["power"]) * (1.0 - protection)
    return np.clip(output * gain[..., None], 0.0, 1.0)


def bake_cube(control: np.ndarray) -> tuple[np.ndarray, dict]:
    grid = np.linspace(0, 1, LUT_SIZE)
    redfast = np.array([(r, g, b) for b in grid for g in grid for r in grid], dtype=np.float64)
    unclipped = evaluate_lattice(control, redfast)
    clipped = np.clip(np.round(unclipped, 9), 0.0, 1.0)
    clipped[clipped == 0.0] = 0.0  # canonicalize negative zero for .cube text
    details = {
        "unclipped_min": float(unclipped.min()),
        "unclipped_max": float(unclipped.max()),
        "clipped_channel_count": int(np.count_nonzero((unclipped < 0) | (unclipped > 1))),
        "clipped_channel_fraction": float(np.mean((unclipped < 0) | (unclipped > 1))),
        "cube_sample_count": int(unclipped.shape[0]),
    }
    return clipped.reshape((LUT_SIZE, LUT_SIZE, LUT_SIZE, 3)), details


def summarize_errors(inputs: np.ndarray, expected: np.ndarray, predicted: np.ndarray, *, include_per_color: bool = False) -> dict:
    if not np.isfinite(inputs).all() or not np.isfinite(expected).all() or not np.isfinite(predicted).all():
        raise ValueError("fit measurements or predictions contain non-finite values")
    absolute = np.abs(predicted - expected)
    de = delta_e76(predicted, expected)
    result = {
        "count": int(len(inputs)),
        "mean_abs_rgb": absolute.mean(axis=0).tolist(),
        "p95_abs_rgb": np.percentile(absolute, 95, axis=0).tolist(),
        "max_abs_rgb": absolute.max(axis=0).tolist(),
        "mean_delta_e76": float(de.mean()),
        "p95_delta_e76": float(np.percentile(de, 95)),
        "max_delta_e76": float(de.max()),
    }
    if include_per_color:
        result["per_color"] = [
            {"input_rgb": row.tolist(), "expected_rgb": target.tolist(), "predicted_rgb": actual.tolist(),
             "abs_rgb": np.abs(actual - target).tolist(), "delta_e76": float(error)}
            for row, target, actual, error in zip(inputs, expected, predicted, de)
        ]
    return result


def luma_monotonicity(control: np.ndarray) -> dict:
    ramp = np.repeat(np.linspace(0, 1, 257)[:, None], 3, axis=1)
    output = np.clip(evaluate_lattice(control, ramp), 0, 1)
    linear = srgb_to_linear(output)
    luma = linear @ np.array([0.2126, 0.7152, 0.0722])
    differences = np.diff(luma)
    return {"monotonic": bool(np.all(differences >= -1e-8)), "minimum_step": float(differences.min()), "decreasing_steps": int(np.count_nonzero(differences < -1e-8))}


def write_cube(path: pathlib.Path, cube: np.ndarray, *, title: str = "Gallery CNT calibrated (measured)") -> None:
    with path.open("x", encoding="ascii") as stream:
        stream.write(f'TITLE "{title}"\n')
        stream.write(f"LUT_3D_SIZE {LUT_SIZE}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n")
        for row in cube.reshape((-1, 3)):
            stream.write("%.9f %.9f %.9f\n" % tuple(row))


def command_fit(args: argparse.Namespace) -> int:
    if getattr(args, "spatial", False):
        return command_fit_spatial(args)
    manifest_path = args.manifest.expanduser()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    neutral, neutral_info = load_srgb_image(args.neutral.expanduser())
    reference, reference_info = load_srgb_image(args.reference.expanduser())
    expected_size = [manifest["width"], manifest["height"]]
    if neutral_info["size"] != expected_size or reference_info["size"] != expected_size:
        raise ValueError(f"image dimensions must match manifest {expected_size}; neutral={neutral_info['size']}, reference={reference_info['size']}")
    if neutral_info["size"] != reference_info["size"] or neutral_info["orientation"] != reference_info["orientation"]:
        raise ValueError("neutral and reference dimensions/orientation do not match")
    output = args.output.expanduser()
    ensure_new_directory(output)

    samples = manifest["samples"]
    measured_input, measured_output = [], []
    sample_records = []
    groups: dict[str, list[dict]] = defaultdict(list)
    for sample in samples:
        source = patch_median(neutral, sample["rect"], manifest["interior_inset"])
        target = patch_median(reference, sample["rect"], manifest["interior_inset"])
        record = dict(sample)
        record["measured_neutral_rgb"] = source.tolist()
        record["measured_reference_rgb"] = target.tolist()
        sample_records.append(record)
        if sample["kind"] in ("train", "grayscale"):
            measured_input.append(source)
            measured_output.append(target)
        if sample["kind"] == "repeat":
            groups[sample["repeat_group"]].append(record)
    train_x, train_y = np.asarray(measured_input), np.asarray(measured_output)
    validation = [sample for sample in sample_records if sample["kind"] == "validation"]
    validation_x = np.asarray([sample["measured_neutral_rgb"] for sample in validation])
    validation_y = np.asarray([sample["measured_reference_rgb"] for sample in validation])
    intended_train = np.asarray([sample["rgb"] for sample in samples if sample["kind"] in ("train", "grayscale")])
    input_roundtrip = np.abs(train_x - intended_train)

    if not all(np.isfinite(values).all() for values in (train_x, train_y, validation_x, validation_y, intended_train)):
        raise ValueError("chart measurements contain non-finite values")

    fitted = {size: fit_lattice(train_x, train_y, size) for size in FIT_SIZES}
    baked = {size: bake_cube(fitted[size]) for size in FIT_SIZES}
    validation_predictions = {size: evaluate_lattice(baked[size][0], validation_x) for size in FIT_SIZES}
    errors = {size: summarize_errors(validation_x, validation_y, validation_predictions[size]) for size in FIT_SIZES}
    improvement = errors[5]["mean_delta_e76"] - errors[7]["mean_delta_e76"]
    required_improvement = max(MEAN_DELTA_E_MEANINGFUL, errors[5]["mean_delta_e76"] * 0.02)
    selected_size = 7 if improvement >= required_improvement else 5
    selected = fitted[selected_size]
    selected_cube, clipping = baked[selected_size]
    unclipped_validation = evaluate_lattice(selected, validation_x)
    validation_clipping = {
        "unclipped_min": float(unclipped_validation.min()),
        "unclipped_max": float(unclipped_validation.max()),
        "clipped_color_count": int(np.count_nonzero(np.any((unclipped_validation < 0) | (unclipped_validation > 1), axis=1))),
        "clipped_color_fraction": float(np.mean(np.any((unclipped_validation < 0) | (unclipped_validation > 1), axis=1))),
    }
    selected_validation_per_color = summarize_errors(validation_x, validation_y, validation_predictions[selected_size], include_per_color=True)["per_color"]

    grayscale_samples = sorted((sample for sample in sample_records if sample["kind"] == "grayscale"), key=lambda s: s["level"])
    gray_input = np.asarray([sample["measured_neutral_rgb"] for sample in grayscale_samples])
    gray_output = evaluate_lattice(selected_cube, gray_input)
    gray_linear = srgb_to_linear(gray_output)
    gray_luma = gray_linear @ np.array([0.2126, 0.7152, 0.0722])
    spread = []
    for group, repeats in sorted(groups.items()):
        neutral_vals = np.asarray([item["measured_neutral_rgb"] for item in repeats])
        vals = np.asarray([item["measured_reference_rgb"] for item in repeats])
        spread.append({
            "group": group,
            "intended_rgb": repeats[0]["rgb"],
            "neutral_max_channel_range": float(np.ptp(neutral_vals, axis=0).max()),
            "neutral_mean_channel_stddev": float(neutral_vals.std(axis=0).mean()),
            "reference_max_channel_range": float(np.ptp(vals, axis=0).max()),
            "reference_mean_channel_stddev": float(vals.std(axis=0).mean()),
        })

    cube_path = output / "GalleryCNTCalibrated.cube"
    write_cube(cube_path, selected_cube)
    model_path = output / "model.json"
    model = {
        "format": "loupe-gallery-cnt-calibrated-model-v1",
        "label": "Gallery CNT calibrated",
        "source": "offline same-input sRGB chart calibration",
        "raw_base_modified": False,
        "grain": "none",
        "input_domain": "encoded SDR sRGB [0,1]",
        "grid_size": selected_size,
        "control_lattice_rgb": selected.tolist(),
        "baked_lut_clipping": clipping,
        "cube": cube_path.name,
        "fit_sizes_compared": list(FIT_SIZES),
        "validation_selection": {"mean_delta_e76_improvement_7_over_5": improvement, "required_mean_improvement": required_improvement, "selected": selected_size},
        "regularization": {"identity": IDENTITY_PENALTY, "second_difference": SMOOTHNESS_PENALTY},
    }
    with model_path.open("x", encoding="utf-8") as stream:
        json.dump(model, stream, indent=2, sort_keys=True)
        stream.write("\n")
    report = {
        "format": "loupe-gallery-cnt-calibration-report-v1",
        "images": {"neutral": {**neutral_info, "path": str(args.neutral)}, "reference": {**reference_info, "path": str(args.reference)}},
        "chart": {"manifest": str(manifest_path), "dimensions": expected_size, "patches": {kind: sum(s["kind"] == kind for s in samples) for kind in ("train", "validation", "grayscale", "repeat")}},
        "neutral_export_roundtrip_vs_intended_input": {"mean_abs_rgb": input_roundtrip.mean(axis=0).tolist(), "p95_abs_rgb": np.percentile(input_roundtrip, 95, axis=0).tolist(), "max_abs_rgb": input_roundtrip.max(axis=0).tolist()},
        "validation_by_grid_size": {str(size): errors[size] for size in FIT_SIZES},
        "selected_validation_per_color": selected_validation_per_color,
        "unclipped_validation_predictions": validation_clipping,
        "baked_lut_clipping": clipping,
        "selected_grid_size": selected_size,
        "grayscale_output_luminance": {"monotonic": bool(np.all(np.diff(gray_luma) >= -1e-8)), "minimum_step": float(np.diff(gray_luma).min()), "decreasing_steps": int(np.count_nonzero(np.diff(gray_luma) < -1e-8))},
        "selected_model_luminance_monotonicity": luma_monotonicity(selected),
        "context_repeat_spread_neutral_export": {"groups": len(spread), "maximum_channel_range": max((x["neutral_max_channel_range"] for x in spread), default=0.0), "mean_channel_range": float(np.mean([x["neutral_max_channel_range"] for x in spread])) if spread else 0.0},
        "context_repeat_spread_reference_export": {"groups": len(spread), "maximum_channel_range": max((x["reference_max_channel_range"] for x in spread), default=0.0), "mean_channel_range": float(np.mean([x["reference_max_channel_range"] for x in spread])) if spread else 0.0},
        "context_repeat_spread_by_color": spread,
        "outputs": {"cube": cube_path.name, "model": model_path.name},
    }
    report_path = output / "report.json"
    with report_path.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(f"Selected {selected_size}^3 control lattice; deployed held-out mean ΔE76={errors[selected_size]['mean_delta_e76']:.3f}")
    print(f"Neutral roundtrip mean abs RGB={input_roundtrip.mean(axis=0).round(5).tolist()}; baked LUT clipped-channel fraction={clipping['clipped_channel_fraction']:.4%}")
    print(f"Held-out unclipped range=[{validation_clipping['unclipped_min']:.4f}, {validation_clipping['unclipped_max']:.4f}], clipped colors={validation_clipping['clipped_color_fraction']:.2%}; repeated-patch spread neutral/reference={report['context_repeat_spread_neutral_export']['maximum_channel_range']:.4f}/{report['context_repeat_spread_reference_export']['maximum_channel_range']:.4f}")
    print(f"Wrote {cube_path}, {model_path}, and {report_path}")
    return 0


def spatial_gain(base: np.ndarray, radial_squared: np.ndarray, parameters: np.ndarray, brightness_kind: str) -> np.ndarray:
    bounded = np.clip(base, 0.0, 1.0)
    brightness = bounded.max(axis=1) if brightness_kind == "maxRGB" else bounded @ np.array([0.2126, 0.7152, 0.0722])
    strength, start, power, highlight_low, highlight_high = parameters
    radial = np.clip((radial_squared - start) / (1.0 - start), 0.0, 1.0)
    t = np.clip((brightness - highlight_low) / (highlight_high - highlight_low), 0.0, 1.0)
    protection = t * t * (3.0 - 2.0 * t)
    return (1.0 - strength * radial**power * (1.0 - protection))[:, None]


def command_fit_spatial(args: argparse.Namespace) -> int:
    manifest_path = args.manifest.expanduser()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    chart_name = manifest.get("chart")
    if not isinstance(chart_name, str) or pathlib.Path(chart_name).name != chart_name:
        raise ValueError("manifest chart must be a filename in the manifest directory")
    chart_path = manifest_path.parent / chart_name
    chart, chart_info = load_srgb_image(chart_path)
    neutral, neutral_info = load_srgb_image(args.neutral.expanduser())
    reference, reference_info = load_srgb_image(args.reference.expanduser())
    expected_size = [manifest["width"], manifest["height"]]
    if any(info["size"] != expected_size for info in (chart_info, neutral_info, reference_info)):
        raise ValueError(f"chart, neutral, and reference must match manifest dimensions {expected_size}")

    samples = manifest["samples"]
    intended, source_chart, source_neutral, target, uv, kind = [], [], [], [], [], []
    for sample in samples:
        intended.append(sample["rgb"])
        source_chart.append(patch_median(chart, sample["rect"], manifest["interior_inset"]))
        source_neutral.append(patch_median(neutral, sample["rect"], manifest["interior_inset"]))
        target.append(patch_median(reference, sample["rect"], manifest["interior_inset"]))
        x, y, width, height = sample["rect"]
        uv.append([(x + width / 2) / manifest["width"], (y + height / 2) / manifest["height"]])
        kind.append(sample["kind"])
    intended = np.asarray(intended, dtype=np.float64)
    source_chart = np.asarray(source_chart)
    source_neutral = np.asarray(source_neutral)
    target = np.asarray(target)
    uv = np.asarray(uv, dtype=np.float64)
    kind = np.asarray(kind)
    radial_squared = 2.0 * np.sum((uv - 0.5) ** 2, axis=1)
    train = np.isin(kind, ("train", "grayscale"))
    validation = kind == "validation"
    if not all(np.isfinite(a).all() for a in (intended, source_chart, source_neutral, target, uv, radial_squared)):
        raise ValueError("spatial fit chart measurements contain non-finite values")

    # Predeclared candidates: encoded-channel luma or max RGB highlight protection.
    # Validation colors are evaluated only after each fit and never enter LSQR.
    candidates = []
    initial = fit_lattice(source_chart[train], target[train], 7)
    for brightness_kind in ("luma", "maxRGB"):
        control = initial.copy()
        parameters = np.array([0.3, 0.24, 1.3, 0.65, 1.0], dtype=np.float64)
        converged = False
        iterations = 0
        for iterations in range(120):
            previous_control = control.copy()
            base = evaluate_lattice(control, source_chart[train])
            gain = spatial_gain(base, radial_squared[train], parameters, brightness_kind)
            control = fit_lattice(source_chart[train], target[train] / gain, 7)
            base = evaluate_lattice(control, source_chart[train])
            solution = least_squares(
                lambda p: (np.clip(base * spatial_gain(base, radial_squared[train], p, brightness_kind), 0.0, 1.0) - target[train]).ravel(),
                parameters,
                bounds=([0.0, 0.0, 0.3, 0.0, 0.86], [0.9, 0.65, 4.0, 0.85, 1.3]),
                max_nfev=100,
                ftol=1e-10,
                xtol=1e-10,
                gtol=1e-10,
            )
            change = max(float(np.max(np.abs(solution.x - parameters))), float(np.max(np.abs(previous_control - control))))
            parameters = solution.x
            if change < 3e-6:
                converged = True
                break
        baked_cube, bake_info = bake_spatial_cube(control)
        deployed_validation = apply_model(
            {"format": "gallery-cnt-spatial-v3", "input_domain": "encoded SDR sRGB", "spatial_domain": "encoded", "spatial_family": "plateau_power_highlight", "brightness": brightness_kind, "grid_size": 7, "control_lattice_rgb": control.tolist(), "strength": float(parameters[0]), "start_radius_squared": float(parameters[1]), "power": float(parameters[2]), "highlight_low": float(parameters[3]), "highlight_high": float(parameters[4])},
            source_chart[validation], uv[validation], lut=baked_cube,
        )
        validation_error = summarize_errors(source_chart[validation], target[validation], deployed_validation)
        train_base = evaluate_lattice(baked_cube, source_chart[train])
        train_gain = spatial_gain(train_base, radial_squared[train], parameters, brightness_kind)
        train_error = summarize_errors(source_chart[train], target[train], np.clip(train_base * train_gain, 0.0, 1.0))
        candidates.append({
            "brightness": brightness_kind,
            "parameters": parameters,
            "control": control,
            "cube": baked_cube,
            "bake": bake_info,
            "iterations": iterations + 1,
            "converged": converged,
            "train": train_error,
            "validation": validation_error,
        })
    selected_candidate = min(candidates, key=lambda item: item["validation"]["mean_delta_e76"])
    parameters = selected_candidate["parameters"]
    control = selected_candidate["control"]
    model = {
        "format": "gallery-cnt-spatial-v3",
        "label": "Gallery CNT calibrated spatial",
        "scope": "Gallery CNT calibrated look; camera equivalence unproven",
        "input_domain": "encoded SDR sRGB",
        "spatial_domain": "encoded",
        "spatial_family": "plateau_power_highlight",
        "brightness": selected_candidate["brightness"],
        "strength": float(parameters[0]),
        "start_radius_squared": float(parameters[1]),
        "power": float(parameters[2]),
        "highlight_low": float(parameters[3]),
        "highlight_high": float(parameters[4]),
        "grid_size": 7,
        "control_lattice_rgb": control.tolist(),
        "cube_bake": selected_candidate["bake"],
        "raw_base_modified": False,
        "grain": "none",
        "fit_input": "manifest chart JPEG decoded with ICC into encoded sRGB",
        "fit_samples": "train and grayscale only; validation colors used for candidate selection, not fit",
    }
    output = args.output.expanduser()
    ensure_new_directory(output)
    cube_path = output / "GalleryCNTSpatial.cube"
    write_cube(cube_path, selected_candidate["cube"], title="Gallery CNT calibrated spatial v3 (encoded sRGB)")
    model["cube"] = cube_path.name
    model_path = output / "model.json"
    with model_path.open("x", encoding="utf-8") as stream:
        json.dump(model, stream, indent=2, sort_keys=True)
        stream.write("\n")
    neutral_roundtrip = summarize_errors(source_neutral, source_chart, source_neutral)
    report = {
        "format": "gallery-cnt-spatial-fit-report-v1",
        "images": {"chart": chart_info, "neutral": neutral_info, "reference": reference_info},
        "candidate_selection": [
            {"brightness": item["brightness"], "iterations": item["iterations"], "converged": item["converged"], "train": item["train"], "validation": item["validation"], "parameters": item["parameters"].tolist(), "cube_bake": item["bake"]}
            for item in candidates
        ],
        "selected_brightness": selected_candidate["brightness"],
        "neutral_export_roundtrip_vs_chart_input": neutral_roundtrip,
        "outputs": {"model": model_path.name, "cube": cube_path.name},
    }
    report_path = output / "report.json"
    with report_path.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(f"Selected brightness={selected_candidate['brightness']}; validation mean ΔE76={selected_candidate['validation']['mean_delta_e76']:.3f}; converged={selected_candidate['converged']}")
    print(f"Neutral export input-difference mean abs RGB={neutral_roundtrip['mean_abs_rgb']}; wrote {model_path} and {cube_path}")
    return 0


def command_evaluate(args: argparse.Namespace) -> int:
    manifest_path = args.manifest.expanduser()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    chart_name = manifest.get("chart")
    if not isinstance(chart_name, str) or pathlib.Path(chart_name).name != chart_name:
        raise ValueError("manifest chart must be a filename in the manifest directory")
    model = json.loads(args.model.expanduser().read_text(encoding="utf-8"))
    control = validate_model(model)
    chart, chart_info = load_srgb_image(manifest_path.parent / chart_name)
    reference, reference_info = load_srgb_image(args.reference.expanduser())
    expected_size = [manifest["width"], manifest["height"]]
    if chart_info["size"] != expected_size or reference_info["size"] != expected_size:
        raise ValueError(f"chart and reference must match manifest dimensions {expected_size}")
    if model["format"] == "gallery-cnt-spatial-v3":
        baked, bake_info = bake_spatial_cube(control)
    else:
        baked, bake_info = bake_cube(control)

    samples = manifest["samples"]
    source, target, uv, kinds = [], [], [], []
    for sample in samples:
        source.append(patch_median(chart, sample["rect"], manifest["interior_inset"]))
        target.append(patch_median(reference, sample["rect"], manifest["interior_inset"]))
        x, y, width, height = sample["rect"]
        uv.append([(x + width / 2) / manifest["width"], (y + height / 2) / manifest["height"]])
        kinds.append(sample["kind"])
    source, target, uv, kinds = np.asarray(source), np.asarray(target), np.asarray(uv), np.asarray(kinds)
    if not all(np.isfinite(a).all() for a in (source, target, uv)):
        raise ValueError("evaluation measurements contain non-finite values")
    predicted = apply_model(model, source, uv, lut=baked)
    rows = {kind: summarize_errors(source[kinds == kind], target[kinds == kind], predicted[kinds == kind])
            for kind in ("train", "validation", "grayscale", "repeat")}
    radial = 2 * np.sum((uv - 0.5) ** 2, axis=1)
    by_radius = {}
    for low, high in ((0.0, 0.4), (0.4, 0.6), (0.6, 0.8), (0.8, 1.01)):
        mask = (radial >= low) & (radial < high)
        by_radius[f"{low:.1f}-{high:.2f}"] = summarize_errors(source[mask], target[mask], predicted[mask])
    quadrants = {}
    for name, mask in (
        ("top_left", (uv[:, 0] < 0.5) & (uv[:, 1] < 0.5)),
        ("top_right", (uv[:, 0] >= 0.5) & (uv[:, 1] < 0.5)),
        ("bottom_left", (uv[:, 0] < 0.5) & (uv[:, 1] >= 0.5)),
        ("bottom_right", (uv[:, 0] >= 0.5) & (uv[:, 1] >= 0.5)),
    ):
        quadrants[name] = summarize_errors(source[mask], target[mask], predicted[mask])

    output = args.output.expanduser()
    ensure_new_directory(output)
    report = {
        "format": "gallery-cnt-spatial-evaluation-v1" if model["format"] == "gallery-cnt-spatial-v3" else "gallery-cnt-global-evaluation-v1",
        "model_format": model["format"],
        "chart": {"manifest": manifest_path.name, "dimensions": expected_size, "patches": {kind: int(np.count_nonzero(kinds == kind)) for kind in ("train", "validation", "grayscale", "repeat")}},
        "images": {"chart": chart_info, "reference": reference_info},
        "baked_lut": bake_info,
        "actual_deployed_33_cube_metrics": {"all": summarize_errors(source, target, predicted), "by_kind": rows, "by_radius_squared": by_radius, "quadrants": quadrants},
        "refit_performed": False,
    }
    report_path = output / "summary.json"
    with report_path.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
    v = rows["validation"]
    print(f"Baked 33^3 evaluation (no refit): held-out mean ΔE76={v['mean_delta_e76']:.3f}, p95={v['p95_delta_e76']:.3f}; summary={report_path}")
    return 0


def load_apply_image(path: pathlib.Path) -> tuple[Image.Image, bytes, dict]:
    with Image.open(path) as opened:
        icc = opened.info.get("icc_profile")
        if not icc:
            raise ValueError(f"{path}: embedded ICC profile is required")
        orientation = opened.getexif().get(274, 1)
        oriented = ImageOps.exif_transpose(opened)
        if oriented.mode not in ("RGB", "RGBA"):
            raise ValueError(f"{path}: expected RGB image, got mode {oriented.mode}")
        if oriented.mode == "RGBA":
            if np.any(np.asarray(oriented.getchannel("A")) != 255):
                raise ValueError(f"{path}: transparency is not supported")
            oriented = oriented.convert("RGB")
        srgb_profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))
        converted = ImageCms.profileToProfile(oriented, ImageCms.ImageCmsProfile(io.BytesIO(icc)), srgb_profile, outputMode="RGB")
        if converted.width * converted.height > 100_000_000:
            raise ValueError("image exceeds the 100 megapixel offline apply limit")
        return converted.copy(), srgb_profile.tobytes(), {"size": [converted.width, converted.height], "source_orientation": orientation, "icc_bytes": len(icc)}


def command_apply(args: argparse.Namespace) -> int:
    model = json.loads(args.model.expanduser().read_text(encoding="utf-8"))
    control = validate_model(model)
    if model["format"] == "gallery-cnt-spatial-v3":
        baked, _ = bake_spatial_cube(control)
    else:
        baked, _ = bake_cube(control)
    source, output_icc, image_info = load_apply_image(args.input.expanduser())
    output = args.output.expanduser()
    if output.exists():
        raise FileExistsError(f"output file already exists: {output}")
    if not output.parent.is_dir():
        raise FileNotFoundError(f"output directory does not exist: {output.parent}")
    suffix = output.suffix.lower()
    if suffix not in (".png", ".jpg", ".jpeg"):
        raise ValueError("output filename must end in .png, .jpg, or .jpeg")
    width, height = source.size
    rendered = Image.new("RGB", (width, height))
    xs = np.arange(width, dtype=np.float64) + 0.5
    for top in range(0, height, 128):
        bottom = min(height, top + 128)
        rgb = np.asarray(source.crop((0, top, width, bottom)), dtype=np.float64) / 255.0
        ys = (np.arange(top, bottom, dtype=np.float64) + 0.5) / height
        xx, yy = np.meshgrid(xs / width, ys)
        uv = np.stack([xx, yy], axis=-1)
        rendered.paste(Image.fromarray(np.rint(apply_model(model, rgb, uv, lut=baked) * 255).astype(np.uint8), "RGB"), (0, top))
    image_format = "PNG" if suffix == ".png" else "JPEG"
    created = False
    try:
        with output.open("xb") as stream:
            created = True
            save_options = {"icc_profile": output_icc}
            if image_format == "JPEG":
                save_options.update({"quality": 100, "subsampling": 0, "optimize": False})
            rendered.save(stream, format=image_format, **save_options)
    except Exception:
        if created and output.exists():
            output.unlink()
        raise
    print(f"Applied {model['format']} to {image_info['size'][0]}x{image_info['size'][1]} sRGB image in 128-row tiles; output={output}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("generate", help="create deterministic same-input sRGB chart")
    generate.add_argument("--output", type=pathlib.Path, required=True, help="new output directory")
    generate.add_argument("--seed", type=int, required=True)
    generate.set_defaults(func=command_generate)
    fit = commands.add_parser("fit", help="fit and validate a measured encoded-sRGB 3D LUT")
    fit.add_argument("--manifest", type=pathlib.Path, required=True)
    fit.add_argument("--neutral", type=pathlib.Path, required=True, help="same-size neutral app export")
    fit.add_argument("--reference", type=pathlib.Path, required=True, help="same-size intended-result export")
    fit.add_argument("--output", type=pathlib.Path, required=True, help="new output directory")
    fit.add_argument("--spatial", action="store_true", help="fit the predeclared Gallery-only spatial v3 candidates")
    fit.set_defaults(func=command_fit)
    evaluate = commands.add_parser("evaluate", help="evaluate a frozen model against held-out chart patches without refitting")
    evaluate.add_argument("--model", type=pathlib.Path, required=True)
    evaluate.add_argument("--manifest", type=pathlib.Path, required=True)
    evaluate.add_argument("--reference", type=pathlib.Path, required=True)
    evaluate.add_argument("--output", type=pathlib.Path, required=True, help="new output directory")
    evaluate.set_defaults(func=command_evaluate)
    apply_parser = commands.add_parser("apply", help="apply a frozen Gallery-only model to an ICC-profiled SDR image")
    apply_parser.add_argument("--model", type=pathlib.Path, required=True)
    apply_parser.add_argument("--input", type=pathlib.Path, required=True)
    apply_parser.add_argument("--output", type=pathlib.Path, required=True, help="new .png/.jpg/.jpeg file; existing files are preserved")
    apply_parser.set_defaults(func=command_apply)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        return args.func(args)
    except (OSError, ValueError, KeyError, RuntimeError, ImageCms.PyCMSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
