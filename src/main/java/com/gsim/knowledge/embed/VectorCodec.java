package com.gsim.knowledge.embed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * float32 little-endian 向量编码/解码工具。
 * 用于 chunk_embeddings.vector_blob 的序列化。
 */
public final class VectorCodec {

    private VectorCodec() {}

    /**
     * 将 float[] 编码为 float32 little-endian byte[]。
     */
    public static byte[] encodeFloat32(float[] values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    /**
     * 将 float32 little-endian byte[] 解码为 float[]。
     */
    public static float[] decodeFloat32(byte[] blob, int dimensions) {
        if (blob.length != dimensions * Float.BYTES) {
            throw new IllegalArgumentException(
                    "Blob length " + blob.length + " does not match dimensions " + dimensions
                    + " (expected " + (dimensions * Float.BYTES) + ")");
        }
        ByteBuffer buf = ByteBuffer.wrap(blob);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            values[i] = buf.getFloat();
        }
        return values;
    }
}
