package com.hyunchang.bioagent.service;

import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 메모리 바이트 배열을 MultipartFile 인터페이스로 감싸는 경량 어댑터.
 * 주로 ZIP 대량 업로드에서 내부 재호출용.
 */
class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    InMemoryMultipartFile(String originalFilename, String contentType, byte[] content) {
        this.name = "file";
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content != null ? content : new byte[0];
    }

    @Override @NonNull public String getName() { return name; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override @NonNull public byte[] getBytes() { return content; }
    @Override @NonNull public InputStream getInputStream() { return new ByteArrayInputStream(content); }

    @Override
    public void transferTo(@NonNull File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }

    @Override
    public void transferTo(@NonNull Path dest) throws IOException {
        Files.write(dest, content);
    }
}
