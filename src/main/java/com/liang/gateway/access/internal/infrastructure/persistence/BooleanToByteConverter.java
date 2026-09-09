package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public final class BooleanToByteConverter implements Converter<Boolean, Byte> {

    @Override
    public Byte convert(Boolean source) {
        return source != null && source ? (byte) 1 : (byte) 0;
    }
}
