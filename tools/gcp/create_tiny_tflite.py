#!/usr/bin/env python3
"""Create a tiny INT8-quantized TFLite model with a single Add op.
This is used to compile two DLA binaries (with/without import_forever)
to compare binary formats and find the import_forever flag offset.
"""
import struct, pathlib, sys

def write_flatbuffer_model(output_path: str) -> None:
    """Write a minimal quantized TFLite model with single Add op.
    Uses flatbuffers binary format manually for a 1-tensor Add model.
    """
    import numpy as np

    try:
        import tensorflow as tf
        # Create a simple quantized model via TF Lite converter
        @tf.function(input_signature=[
            tf.TensorSpec(shape=[1, 128], dtype=tf.float32)
        ])
        def add_fn(x):
            return x + x

        converter = tf.lite.TFLiteConverter.from_concrete_functions(
            [add_fn.get_concrete_function()], add_fn
        )
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.int8]
        tflite_model = converter.convert()
        with open(output_path, 'wb') as f:
            f.write(tflite_model)
        print(f"Wrote quantized TFLite model ({len(tflite_model)} bytes) to {output_path}")
    except Exception as e:
        print(f"TF not available ({e}), trying ai_edge_litert approach...")
        import ai_edge_litert
        # Fallback: just copy the existing tiny model
        raise e

if __name__ == "__main__":
    output = sys.argv[1] if len(sys.argv) > 1 else "/tmp/tiny_add.tflite"
    write_flatbuffer_model(output)
