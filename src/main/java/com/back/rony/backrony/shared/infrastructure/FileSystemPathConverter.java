package com.back.rony.backrony.shared.infrastructure;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Configuration paths refer to the filesystem, not classpath resources. */
@Component
@ConfigurationPropertiesBinding
public final class FileSystemPathConverter implements Converter<String, Path> {
    @Override
    public Path convert(String source) {
        return Path.of(source).toAbsolutePath().normalize();
    }
}
