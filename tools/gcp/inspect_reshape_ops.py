#!/usr/bin/env python3
"""Inspect a TFLite model for dynamic RESHAPE ops (shape specified as tensor input).

TFLite RESHAPE op: opcode 22
- Input 0: tensor to reshape
- Input 1: new shape (index -1 = absent/const, or tensor index = dynamic)

A "dynamic" reshape is one where input 1 is a VARIABLE (non-constant) tensor.
These cause MapReshapeOps failures in NeuronAdapter v9 without import_forever.
"""
import sys
import struct

def read_u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]

def read_i32(data, offset):
    return struct.unpack_from('<i', data, offset)[0]

def read_flatbuffer_table(data, offset):
    """Returns (vtable_offset, data_offset) for a flatbuffer table at offset."""
    indirect = read_i32(data, offset)
    vtable_offset = offset - indirect
    return vtable_offset, offset

def inspect_model(path):
    with open(path, 'rb') as f:
        data = bytes(f.read())

    print(f"Model: {path}")
    print(f"Size: {len(data):,} bytes")
    print(f"Identifier: {data[4:8]!r}")

    # TFLite flatbuffer root is at the offset stored at byte 0
    root_offset = read_u32(data, 0)

    # We'll use a simpler approach: search for RESHAPE op pattern
    # RESHAPE TFLite opcode is 22 (0x16)

    # Try using TFLite interpreter for tensor info
    try:
        from ai_edge_litert.runtime import interpreter as tflite_interp
        interp = tflite_interp.Interpreter(model_path=path)
        interp.allocate_tensors()

        input_details = interp.get_input_details()
        output_details = interp.get_output_details()
        tensor_details = interp.get_tensor_details()

        print(f"\nModel inputs: {len(input_details)}")
        for d in input_details:
            print(f"  [{d['index']}] {d['name']}: shape={d['shape']}, dtype={d['dtype']}")

        print(f"\nModel outputs: {len(output_details)}")
        for d in output_details:
            print(f"  [{d['index']}] {d['name']}: shape={d['shape']}, dtype={d['dtype']}")

        print(f"\nTotal tensors: {len(tensor_details)}")

        # Find tensors with name containing "shape" or that might be shape tensors
        shape_tensors = [d for d in tensor_details if 'shape' in d['name'].lower()]
        print(f"Shape-named tensors: {len(shape_tensors)}")
        for d in shape_tensors[:20]:
            print(f"  [{d['index']}] {d['name']}: shape={d['shape']}, dtype={d['dtype']}")
    except Exception as e:
        print(f"Interpreter inspection failed: {e}")

    # Try flatbuffers-based parsing to find subgraph operators
    try:
        import flatbuffers
        from flatbuffers import encode, number_types

        # Navigate flatbuffer: Model -> subgraphs[0] -> operators
        # TFLite Model table structure (schema-specific offsets)
        # We'll try to count RESHAPE ops by looking at raw opcode bytes

        # Search for the string "RESHAPE" or opcode 22 in operator codes
        reshape_count = data.count(b'\x16\x00\x00\x00')  # int32 = 22
        print(f"\nPotential RESHAPE opcode occurrences (raw): {reshape_count}")

    except Exception as e:
        print(f"Flatbuffers inspection failed: {e}")


if __name__ == '__main__':
    inspect_model(sys.argv[1] if len(sys.argv) > 1 else 'model.tflite')
