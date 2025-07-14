package com.MoleLaw_backend.util;

import java.nio.ByteBuffer;

public class VectorUtil {
    public static byte[] toByteArray(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * vector.length);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static float[] fromByteArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}

