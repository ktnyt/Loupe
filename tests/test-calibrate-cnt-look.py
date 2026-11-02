#!/usr/bin/env python3
"""Synthetic regression checks for scripts/calibrate-cnt-look.py."""

import importlib.util
import json
import pathlib
import tempfile
import unittest
import warnings

import numpy as np
from PIL import Image


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "scripts" / "calibrate-cnt-look.py"
SPEC = importlib.util.spec_from_file_location("calibrate_cnt_look", SCRIPT_PATH)
calibration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(calibration)


class CalibrateCntLookTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cnt-look-test-")
        self.root = pathlib.Path(self.temp.name)
        self.manifest_path = self.root / "manifest.json"
        self.chart_path = self.root / "chart.jpg"
        samples = calibration.make_samples(117)
        width, height = calibration.render_chart(samples, self.chart_path, 117)
        self.manifest = {
            "format": "loupe-cnt-look-chart-v1",
            "seed": 117,
            "chart": "chart.jpg",
            "width": width,
            "height": height,
            "orientation": 1,
            "patch_size": calibration.PATCH_SIZE,
            "gutter": calibration.GUTTER,
            "interior_inset": calibration.INTERIOR_INSET,
            "samples": samples,
        }
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def _fit(self, reference_path, name):
        output = self.root / name
        args = type("Args", (), {
            "manifest": self.manifest_path,
            "neutral": self.chart_path,
            "reference": reference_path,
            "output": output,
        })()
        calibration.command_fit(args)
        return output

    @staticmethod
    def _spatial_model(strength=0.3, control=None):
        if control is None:
            control = np.array([(r / 6, g / 6, b / 6) for b in range(7) for g in range(7) for r in range(7)]).reshape((7, 7, 7, 3))
        return {
            "format": "gallery-cnt-spatial-v3",
            "input_domain": "encoded SDR sRGB",
            "spatial_domain": "encoded",
            "spatial_family": "plateau_power_highlight",
            "brightness": "luma",
            "strength": strength,
            "start_radius_squared": 0.24,
            "power": 1.3,
            "highlight_low": 0.5,
            "highlight_high": 1.0,
            "grid_size": 7,
            "control_lattice_rgb": np.asarray(control).tolist(),
            "raw_base_modified": False,
            "grain": "none",
        }

    def test_chart_is_deterministic_and_validation_is_disjoint(self):
        first = self.root / "generated-a"
        second = self.root / "generated-b"
        calibration.command_generate(type("Args", (), {"output": first, "seed": 987})())
        calibration.command_generate(type("Args", (), {"output": second, "seed": 987})())
        self.assertEqual((first / "chart.jpg").read_bytes(), (second / "chart.jpg").read_bytes())
        manifest_a = json.loads((first / "manifest.json").read_text(encoding="utf-8"))
        manifest_b = json.loads((second / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest_a, manifest_b)
        repeat_colors_a = {sample["repeat_group"]: tuple(sample["rgb"]) for sample in manifest_a["samples"] if sample["kind"] == "repeat"}
        manifest_c_dir = self.root / "generated-c"
        calibration.command_generate(type("Args", (), {"output": manifest_c_dir, "seed": 988})())
        manifest_c = json.loads((manifest_c_dir / "manifest.json").read_text(encoding="utf-8"))
        repeat_colors_c = {sample["repeat_group"]: tuple(sample["rgb"]) for sample in manifest_c["samples"] if sample["kind"] == "repeat"}
        self.assertEqual(repeat_colors_a, repeat_colors_c)
        train = {tuple(sample["rgb"]) for sample in manifest_a["samples"] if sample["kind"] == "train"}
        validation = {tuple(sample["rgb"]) for sample in manifest_a["samples"] if sample["kind"] == "validation"}
        self.assertEqual(len(train), 17**3)
        self.assertEqual(len(validation), 8**3)
        self.assertTrue(train.isdisjoint(validation))
        repeats = [sample for sample in manifest_a["samples"] if sample["kind"] == "repeat"]
        self.assertEqual(len(repeats), calibration.REPEATED_COLOR_COUNT * calibration.REPEAT_COUNT)
        self.assertEqual(manifest_a["jpeg"], {"quality": 100, "subsampling": 0})
        with Image.open(first / "chart.jpg") as chart:
            self.assertTrue(chart.info.get("icc_profile"))

    def test_identity_roundtrip_has_low_heldout_error_and_valid_cube_order(self):
        output = self._fit(self.chart_path, "identity-fit")
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        identity_error = report["validation_by_grid_size"]["5"]["mean_delta_e76"]
        self.assertLess(identity_error, 1.0)
        self.assertEqual(report["selected_grid_size"], 5)
        self.assertLess(max(report["neutral_export_roundtrip_vs_intended_input"]["max_abs_rgb"]), 0.03)
        cube = (output / "GalleryCNTCalibrated.cube").read_text(encoding="ascii").splitlines()
        self.assertIn("LUT_3D_SIZE 33", cube)
        data = [line for line in cube if line and line[0].isdigit()]
        self.assertEqual(len(data), 33**3)
        rows = np.asarray([[float(v) for v in line.split()] for line in data])
        np.testing.assert_allclose(rows[0], [0, 0, 0], atol=0.005)
        np.testing.assert_allclose(rows[1], [1 / 32, 0, 0], atol=0.01)
        np.testing.assert_allclose(rows[33], [0, 1 / 32, 0], atol=0.01)
        np.testing.assert_allclose(rows[-1], [1, 1, 1], atol=0.005)

    def test_smooth_nonlinear_mapping_is_measured_on_heldout_colors(self):
        with Image.open(self.chart_path) as source:
            rgb = np.asarray(source.convert("RGB"), dtype=np.float64) / 255
        r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
        transformed = np.stack([
            0.90 * r + 0.10 * r * g,
            np.power(g, 1.12),
            0.92 * b + 0.08 * r * b,
        ], axis=-1)
        reference_path = self.root / "reference.jpg"
        Image.fromarray(np.rint(np.clip(transformed, 0, 1) * 255).astype(np.uint8), "RGB").save(
            reference_path, quality=100, subsampling=0, icc_profile=calibration.srgb_profile_bytes())
        output = self._fit(reference_path, "nonlinear-fit")
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        selected = report["selected_grid_size"]
        validation = report["validation_by_grid_size"][str(selected)]
        self.assertEqual(validation["count"], 8**3)
        self.assertLess(validation["mean_delta_e76"], 2.5)
        self.assertEqual(len(report["selected_validation_per_color"]), 8**3)
        self.assertEqual(report["context_repeat_spread_reference_export"]["groups"], calibration.REPEATED_COLOR_COUNT)
        self.assertEqual(report["context_repeat_spread_neutral_export"]["groups"], calibration.REPEATED_COLOR_COUNT)
        self.assertEqual(report["chart"]["patches"]["repeat"], calibration.REPEATED_COLOR_COUNT * calibration.REPEAT_COUNT)

    def test_rejects_missing_profile_and_size_mismatch(self):
        no_profile = self.root / "no-profile.jpg"
        Image.new("RGB", (20, 20), (50, 70, 90)).save(no_profile, quality=100)
        with self.assertRaisesRegex(ValueError, "ICC profile is required"):
            calibration.load_srgb_image(no_profile)

        wrong_size = self.root / "wrong-size.jpg"
        Image.new("RGB", (20, 20), (50, 70, 90)).save(
            wrong_size, quality=100, icc_profile=calibration.srgb_profile_bytes())
        output = self.root / "must-not-exist"
        args = type("Args", (), {
            "manifest": self.manifest_path,
            "neutral": self.chart_path,
            "reference": wrong_size,
            "output": output,
        })()
        with self.assertRaisesRegex(ValueError, "dimensions must match manifest"):
            calibration.command_fit(args)
        self.assertFalse(output.exists())

    def test_baked_predictions_are_bounded_and_overshoots_are_reported_without_warnings(self):
        control = np.empty((5, 5, 5, 3), dtype=np.float64)
        control[..., 0] = 1.2
        control[..., 1] = -0.1
        control[..., 2] = 0.5
        cube, clipping = calibration.bake_cube(control)
        self.assertAlmostEqual(clipping["unclipped_min"], -0.1)
        self.assertAlmostEqual(clipping["unclipped_max"], 1.2)
        self.assertGreater(clipping["clipped_channel_fraction"], 0)
        deployed = calibration.evaluate_lattice(cube, np.array([[0.3, 0.5, 0.7]]))
        np.testing.assert_allclose(deployed, [[1.0, 0.0, 0.5]])
        with warnings.catch_warnings():
            warnings.simplefilter("error", RuntimeWarning)
            calibration.rgb_to_lab(np.array([[-0.1, 0.2, 1.2]]))
            calibration.srgb_to_linear(np.array([[-0.1, 0.2, 1.2]]))

    def test_manifest_rejects_overlap_and_nonfinite_color_before_sampling(self):
        bad = json.loads(json.dumps(self.manifest))
        bad["samples"][1]["rect"] = bad["samples"][0]["rect"]
        with self.assertRaisesRegex(ValueError, "rectangle"):
            calibration.validate_manifest(bad)
        bad = json.loads(json.dumps(self.manifest))
        bad["samples"][0]["rgb"][0] = float("nan")
        with self.assertRaisesRegex(ValueError, "finite"):
            calibration.validate_manifest(bad)

    def test_spatial_identity_and_independent_bypass_strength(self):
        rgb = np.array([[0.13, 0.42, 0.83], [0.8, 0.4, 0.2]])
        uv = np.array([[0.0, 0.0], [0.5, 0.5]])
        identity = self._spatial_model(strength=0.0)
        baked, _ = calibration.bake_spatial_cube(np.asarray(identity["control_lattice_rgb"]))
        np.testing.assert_allclose(calibration.apply_model(identity, rgb, uv, lut=baked), rgb, atol=1e-8)

        transformed = np.asarray(identity["control_lattice_rgb"], dtype=np.float64) * 0.8 + 0.05
        model = self._spatial_model(strength=0.5, control=transformed)
        lut, _ = calibration.bake_spatial_cube(transformed)
        base = np.clip(calibration.evaluate_lattice(lut, rgb), 0, 1)
        np.testing.assert_allclose(calibration.apply_model(model, rgb, uv, spatial_enabled=False, lut=lut), base, atol=1e-8)
        model["strength"] = 0.0
        np.testing.assert_allclose(calibration.apply_model(model, rgb, uv, lut=lut), base, atol=1e-8)

    def test_highlights_are_protected_and_spatial_gain_uses_global_corner_coordinates(self):
        model = self._spatial_model(strength=0.4)
        lut, _ = calibration.bake_spatial_cube(np.asarray(model["control_lattice_rgb"]))
        samples = np.array([[0.2, 0.2, 0.2], [0.9, 0.9, 0.9]])
        uv = np.array([[0.0, 0.0], [0.0, 0.0]])
        result = calibration.apply_model(model, samples, uv, lut=lut)
        self.assertGreater(result[1].mean() / samples[1].mean(), result[0].mean() / samples[0].mean())
        center = calibration.apply_model(model, samples[:1], np.array([[0.5, 0.5]]), lut=lut)
        corner = calibration.apply_model(model, samples[:1], np.array([[0.0, 0.0]]), lut=lut)
        np.testing.assert_allclose(center, samples[:1], atol=1e-8)
        self.assertLess(corner.mean(), center.mean())

    def test_spatial_model_validation_and_baked_cube_nine_decimal_order(self):
        model = self._spatial_model()
        control = np.asarray(model["control_lattice_rgb"], dtype=np.float64)
        self.assertEqual(calibration.validate_model(model).shape, (7, 7, 7, 3))
        cube, info = calibration.bake_spatial_cube(control)
        self.assertEqual(info["decimal_places"], 9)
        self.assertTrue(info["red_fastest"])
        grid = np.linspace(0, 1, calibration.LUT_SIZE)
        test_points = np.array([[grid[1], grid[2], grid[3]], [grid[-1], grid[0], grid[4]]])
        actual = calibration.evaluate_lattice(cube, test_points)
        expected = np.round(calibration.evaluate_lattice(control, test_points), 9)
        np.testing.assert_allclose(actual, expected, atol=1e-8)
        cube_path = self.root / "spatial.cube"
        calibration.write_cube(cube_path, cube, title="test spatial cube")
        lines = cube_path.read_text(encoding="ascii").splitlines()
        data = [line for line in lines if line and line[0].isdigit()]
        self.assertEqual(len(data), 33**3)
        self.assertTrue(all(len(value.split(".")[-1]) == 9 for value in data[0].split()))
        np.testing.assert_allclose([float(v) for v in data[1].split()], cube[0, 0, 1], atol=1e-9)
        bad = dict(model)
        bad["control_lattice_rgb"] = np.full((5, 5, 5, 3), np.nan).tolist()
        with self.assertRaisesRegex(ValueError, "shape"):
            calibration.validate_model(bad)
        bad = dict(model, grid_size=5)
        with self.assertRaisesRegex(ValueError, r"7\^3"):
            calibration.validate_model(bad)

    def test_evaluate_uses_baked_cube_without_refitting(self):
        model = self._spatial_model(strength=0.0)
        model_path = self.root / "model.json"
        model_path.write_text(json.dumps(model), encoding="utf-8")
        output = self.root / "evaluation"
        args = type("Args", (), {"model": model_path, "manifest": self.manifest_path,
                                  "reference": self.chart_path, "output": output})()
        calibration.command_evaluate(args)
        report = json.loads((output / "summary.json").read_text(encoding="utf-8"))
        self.assertFalse(report["refit_performed"])
        self.assertEqual(report["baked_lut"]["size"], 33)
        self.assertEqual(report["actual_deployed_33_cube_metrics"]["by_kind"]["validation"]["count"], 8**3)
        self.assertLess(report["actual_deployed_33_cube_metrics"]["by_kind"]["validation"]["mean_delta_e76"], 1.0)

    def test_apply_never_overwrites_existing_output(self):
        model = self._spatial_model(strength=0.0)
        model_path = self.root / "apply-model.json"
        model_path.write_text(json.dumps(model), encoding="utf-8")
        source_path = self.root / "source.png"
        Image.new("RGB", (8, 4), (90, 120, 150)).save(source_path, icc_profile=calibration.srgb_profile_bytes())
        output = self.root / "keep.png"
        output.write_bytes(b"existing user data")
        args = type("Args", (), {"model": model_path, "input": source_path, "output": output})()
        with self.assertRaises(FileExistsError):
            calibration.command_apply(args)
        self.assertEqual(output.read_bytes(), b"existing user data")

    def test_apply_processes_icc_image_in_global_128_row_tiles(self):
        model = self._spatial_model(strength=0.35)
        model_path = self.root / "tiled-model.json"
        model_path.write_text(json.dumps(model), encoding="utf-8")
        source_path = self.root / "tile-source.png"
        height, width = 260, 7
        yy, xx = np.mgrid[0:height, 0:width]
        rgb = np.stack([xx / (width - 1), yy / (height - 1), np.full_like(xx, 0.35)], axis=-1)
        source_pixels = np.rint(rgb * 255).astype(np.uint8)
        Image.fromarray(source_pixels, "RGB").save(source_path, icc_profile=calibration.srgb_profile_bytes())
        output_path = self.root / "tiled-output.png"
        args = type("Args", (), {"model": model_path, "input": source_path, "output": output_path})()
        calibration.command_apply(args)
        with Image.open(output_path) as rendered:
            self.assertTrue(rendered.info.get("icc_profile"))
            actual = np.asarray(rendered.convert("RGB"))
        expected = np.rint(calibration.apply_model(model, source_pixels / 255.0,
                                                   np.stack(np.meshgrid((np.arange(width)+0.5)/width,
                                                                        (np.arange(height)+0.5)/height), axis=-1),
                                                   lut=calibration.bake_spatial_cube(np.asarray(model["control_lattice_rgb"]))[0]) * 255).astype(np.uint8)
        self.assertLessEqual(int(np.max(np.abs(actual.astype(int) - expected.astype(int)))), 1)


if __name__ == "__main__":
    unittest.main()
