package com.liang.gateway.core;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

public record ProxyResponse(HttpStatusCode status, byte[] body, MediaType contentType) {

    public ProxyResponse {
        body = body == null ? new byte[0] : body;
    }
}
