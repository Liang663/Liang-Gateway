package com.liang.gateway.core.internal.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entry point for the optional, classpath-packaged desktop console. */
@RestController
public class ConsoleController {

    @GetMapping({"/console", "/console/"})
    public ResponseEntity<Void> console() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/console/index.html"))
                .build();
    }
}
