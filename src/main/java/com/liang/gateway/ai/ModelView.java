package com.liang.gateway.ai;

public record ModelView(
        String name, String provider, long inputPriceFenPerMillion, long outputPriceFenPerMillion) {}
