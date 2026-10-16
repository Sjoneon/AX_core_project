package com.project.backend.data;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies packaged consultation data against source-manifest.json before any
 * catalog parses it.
 */
@Component
public class DataManifest {
    public static final String MANIFEST = "homecare/source-manifest.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ResourceLoader loader;
    private final String root;
    private final Map<String, Object> manifest;
    private final Map<String, String> hashes = new HashMap<>();

    public DataManifest(@Value("${homecare.data.root:classpath:}") String root, ResourceLoader loader) {
        this.root = root;
        this.loader = loader;
        manifest = JSON.readValue(bytes(MANIFEST), new TypeReference<Map<String, Object>>() {
        });
        if (!(manifest.get("version") instanceof String) || !(manifest.get("files") instanceof Map<?, ?> files)
                || files.isEmpty())
            throw new IllegalStateException("자료 매니페스트에 버전과 파일 해시가 필요합니다.");
        files.forEach((path, hash) -> hashes.put(requireNonNull(path).toString(), requireNonNull(hash).toString()));
    }

    public static DataManifest classpath() {
        return new DataManifest("classpath:", new DefaultResourceLoader());
    }

    public String version() {
        return (String) manifest.get("version");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String key) {
        return manifest.get(key) instanceof Map<?, ?> value ? Map.copyOf((Map<String, Object>) value) : Map.of();
    }

    public int count(String key) {
        if (!(manifest.get(key) instanceof Number n))
            throw new IllegalStateException("매니페스트 건수가 없습니다: " + key);
        return n.intValue();
    }

    /**
     * Returns the file only when its SHA-256 matches the manifest. A mismatch must
     * stop application startup.
     */
    public byte[] read(String path) {
        String expected = hashes.get(path);
        if (expected == null)
            throw new IllegalStateException("매니페스트에 없는 자료입니다: " + path);
        byte[] data = bytes(path);
        if (!expected.equals(sha256(data)))
            throw new IllegalStateException("자료 무결성 검증에 실패했습니다: " + path);
        return data;
    }

    private byte[] bytes(String path) {
        try (var in = loader.getResource(root + path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("자료를 읽을 수 없습니다: " + path, error);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
