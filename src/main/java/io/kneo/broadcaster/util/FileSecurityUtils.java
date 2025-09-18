package io.kneo.broadcaster.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class FileSecurityUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSecurityUtils.class);
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[^/\\\\:\\*\\?\"<>\\|\\x00-\\x1F\\x7F-\\x9F]+$");
    private static final int MAX_FILENAME_LENGTH = 255;

    private static final Pattern[] BLOCKED_PATTERNS = {
            Pattern.compile("^\\.+$"),
            Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.(exe|bat|cmd|scr|pif|vbs|js|jar|com|dll)$", Pattern.CASE_INSENSITIVE)
    };

    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new SecurityException("Filename cannot be null or empty");
        }
        String sanitized = filename.replaceAll("[/\\\\]", "");
        sanitized = sanitized.replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]", "");
        sanitized = sanitized.replaceAll("[:\\*\\?\"<>\\|]", "");
        sanitized = sanitized.trim();
        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            String extension = "";
            int dotIndex = sanitized.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < sanitized.length() - 1) {
                extension = sanitized.substring(dotIndex);
                if (extension.length() > 10) {
                    extension = "";
                }
            }

            int maxBaseName = MAX_FILENAME_LENGTH - extension.length();
            sanitized = sanitized.substring(0, Math.max(1, maxBaseName)) + extension;
        }

        if (!isSecureFilename(sanitized)) {
            throw new SecurityException("Filename contains unsafe characters or patterns: " + filename);
        }

        LOGGER.debug("Sanitized filename '{}' to '{}'", filename, sanitized);
        return sanitized;
    }

    public static boolean isSecureFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        if (!SAFE_FILENAME_PATTERN.matcher(filename).matches()) {
            LOGGER.warn("Filename '{}' contains unsafe characters", filename);
            return false;
        }

        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(filename).matches()) {
                LOGGER.warn("Filename '{}' matches blocked pattern: {}", filename, pattern.pattern());
                return false;
            }
        }

        return true;
    }

    public static Path secureResolve(Path baseDirectory, String filename) {
        String secureFilename = sanitizeFilename(filename);

        try {
            Path resolvedPath = baseDirectory.resolve(secureFilename);
            Path normalizedPath = resolvedPath.normalize();
            Path canonicalBase = baseDirectory.toRealPath();
            Path canonicalResolved = normalizedPath.toRealPath();
            if (!isPathWithinBase(canonicalBase, canonicalResolved)) {
                throw new SecurityException(
                        String.format("Path traversal attempt detected. File '%s' would resolve outside base directory '%s'",
                                filename, baseDirectory));
            }

            return normalizedPath;

        } catch (IOException e) {
            Path resolvedPath = baseDirectory.resolve(secureFilename).normalize();

            if (!isPathWithinBase(baseDirectory.normalize(), resolvedPath)) {
                throw new SecurityException(
                        String.format("Path traversal attempt detected. File '%s' would resolve outside base directory '%s'",
                                filename, baseDirectory));
            }

            return resolvedPath;
        }
    }

    public static boolean isPathWithinBase(Path basePath, Path targetPath) {
        try {
            Path canonicalBase = basePath.toRealPath();
            Path canonicalTarget = targetPath.toRealPath();
            return canonicalTarget.startsWith(canonicalBase);
        } catch (IOException e) {
            return targetPath.normalize().startsWith(basePath.normalize());
        }
    }
}