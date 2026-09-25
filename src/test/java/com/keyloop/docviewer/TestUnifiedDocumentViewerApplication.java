package com.keyloop.docviewer;

import org.springframework.boot.SpringApplication;

public class TestUnifiedDocumentViewerApplication {

    public static void main(String[] args) {
        SpringApplication.from(UnifiedDocumentViewerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
