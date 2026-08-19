#!/usr/bin/env python3

import unittest

from train_bladelow_model import build_generation_preference, dot, generation_features, sigmoid


def feedback_row(outcome: str, width: int, depth: int, floors: int, roof_layers: int) -> dict:
    return {
        "outcome": outcome,
        "selMinX": 0,
        "selMaxX": 11,
        "selMinZ": 0,
        "selMaxZ": 11,
        "previewMinX": 1,
        "previewMaxX": width,
        "previewMinZ": 1,
        "previewMaxZ": depth,
        "bodyWidth": width,
        "bodyDepth": depth,
        "actualFloors": floors,
        "roofLayers": roof_layers,
    }


def predict(model: dict, row: dict) -> float:
    features = generation_features(row)
    assert features is not None
    normalized = [
        (value - model["means"][index]) / model["scales"][index]
        for index, value in enumerate(features)
    ]
    return sigmoid(model["bias"] + dot(model["weights"], normalized))


class GenerationPreferenceTrainingTest(unittest.TestCase):
    def test_balanced_feedback_trains_and_ranks_geometry(self) -> None:
        accepted = [feedback_row("accepted", 7, 7, 2, 3) for _ in range(5)]
        negative = [feedback_row("rerolled", 11, 5, 3, 1) for _ in range(3)]
        negative += [feedback_row("rejected", 11, 5, 3, 1) for _ in range(2)]

        model = build_generation_preference(accepted + negative)

        self.assertTrue(model["enabled"])
        self.assertEqual(10, model["samples"])
        self.assertGreater(
            predict(model, feedback_row("accepted", 7, 7, 2, 3)),
            predict(model, feedback_row("rejected", 11, 5, 3, 1)),
        )

    def test_one_sided_feedback_keeps_fallback_enabled(self) -> None:
        model = build_generation_preference(
            [feedback_row("accepted", 7, 7, 2, 3) for _ in range(12)]
        )

        self.assertFalse(model["enabled"])
        self.assertEqual(0, model["negativeSamples"])


if __name__ == "__main__":
    unittest.main()
