package com.liang.gateway.core.internal.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

final class SseEventFramer {

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private SseEventFramer() {}

    static Flux<byte[]> frame(Flux<DataBuffer> inbound) {
        return Flux.defer(() -> {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            return inbound.concatMapIterable(buffer -> split(acc, readAndRelease(buffer)))
                    .concatWith(Flux.defer(() -> remaining(acc)));
        });
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        try {
            byte[] chunk = new byte[buffer.readableByteCount()];
            buffer.read(chunk);
            return chunk;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static List<byte[]> split(ByteArrayOutputStream acc, byte[] chunk) {
        try {
            acc.write(chunk);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        byte[] data = acc.toByteArray();
        List<byte[]> events = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < data.length) {
            int delimLen = delimiterLength(data, i);
            if (delimLen > 0) {
                int end = i + delimLen;
                events.add(copy(data, start, end));
                start = end;
                i = end;
            } else {
                i++;
            }
        }
        acc.reset();
        if (start < data.length) {
            acc.write(data, start, data.length - start);
        }
        return events;
    }

    private static int delimiterLength(byte[] data, int i) {
        if (i + 3 < data.length
                && data[i] == CR
                && data[i + 1] == LF
                && data[i + 2] == CR
                && data[i + 3] == LF) {
            return 4;
        }
        if (i + 1 < data.length && data[i] == LF && data[i + 1] == LF) {
            return 2;
        }
        return 0;
    }

    private static Flux<byte[]> remaining(ByteArrayOutputStream acc) {
        if (acc.size() == 0) {
            return Flux.empty();
        }
        return Flux.just(acc.toByteArray());
    }

    private static byte[] copy(byte[] data, int start, int end) {
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }
}
