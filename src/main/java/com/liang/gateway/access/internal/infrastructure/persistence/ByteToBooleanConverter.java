package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public final class ByteToBooleanConverter implements Converter<Byte, Boolean> {

    @Override
    public Boolean convert(Byte source) {
        return source != null && source != 0;
    }
}
