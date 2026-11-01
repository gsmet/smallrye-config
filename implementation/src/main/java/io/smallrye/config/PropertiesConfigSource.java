/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import io.smallrye.common.classloader.ClassPathUtils;
import io.smallrye.config.common.utils.ConfigSourceUtil;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
public class PropertiesConfigSource extends MapBackedConfigValueConfigSource {
    @Serial
    private static final long serialVersionUID = 1866835565147832432L;

    public static final String NAME = "PropertiesConfigSource[source=%s]";
    public static final int ORDINAL = DEFAULT_ORDINAL;

    public PropertiesConfigSource(URL url) throws IOException {
        this(url, DEFAULT_ORDINAL);
    }

    public PropertiesConfigSource(URL url, int defaultOrdinal) throws IOException {
        this(url, String.format(NAME, url.toString()), defaultOrdinal);
    }

    private PropertiesConfigSource(URL url, String name, int defaultOrdinal) throws IOException {
        super(name, urlToConfigValueMap(url, name, defaultOrdinal), defaultOrdinal);
    }

    public PropertiesConfigSource(Map<String, String> properties, String name) {
        this(properties, name, DEFAULT_ORDINAL);
    }

    public PropertiesConfigSource(Properties properties, String name) {
        this(ConfigSourceUtil.propertiesToMap(properties), name, DEFAULT_ORDINAL);
    }

    public PropertiesConfigSource(Map<String, String> properties, String name, int defaultOrdinal) {
        this(String.format(NAME, name), properties, defaultOrdinal);
    }

    private PropertiesConfigSource(String name, Map<String, String> properties, int defaultOrdinal) {
        super(name,
                new ConfigValueMapStringView(properties, name, ConfigSourceUtil.getOrdinalFromMap(properties, defaultOrdinal)),
                defaultOrdinal);
    }

    public PropertiesConfigSource(Properties properties, String name, int defaultOrdinal) {
        this(ConfigSourceUtil.propertiesToMap(properties), name, defaultOrdinal);
    }

    public static Map<String, ConfigValue> urlToConfigValueMap(URL locationOfProperties, String name, int ordinal)
            throws IOException {
        ConfigValueProperties properties = new ConfigValueProperties(name, ordinal);
        ClassPathUtils.consumeStream(locationOfProperties, new Consumer<>() {
            @Override
            public void accept(InputStream inputStream) {
                try {
                    // Read the file at once rather than paying for the 8 KB buffers of a BufferedReader, a
                    // StreamDecoder and the line reader on every file. A file whose characters all fit in
                    // ISO-8859-1, which most do, is parsed from its bytes through the byte path of the line
                    // reader, transcoded in place when it has accented characters; anything else is decoded as
                    // UTF-8 first.
                    byte[] bytes = readAll(inputStream);
                    int length = toLatin1(bytes);
                    if (length >= 0) {
                        properties.load(bytes, length);
                    } else {
                        CharBuffer chars = UTF_8.decode(ByteBuffer.wrap(bytes));
                        properties.load(chars.array(), chars.limit());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        return properties;
    }

    /**
     * Reads the stream to the end into an array of exactly its size when the stream announces it, which the
     * streams of a jar entry, of a file and of an in-memory resource do.
     */
    private static byte[] readAll(InputStream inputStream) throws IOException {
        int expected = inputStream.available();
        byte[] bytes = new byte[expected];
        int read = expected == 0 ? 0 : inputStream.readNBytes(bytes, 0, expected);
        if (read < expected) {
            return Arrays.copyOf(bytes, read);
        }
        int next = inputStream.read();
        if (next == -1) {
            return bytes;
        }
        // the stream has more than it announced (or announced nothing): read the rest the generic way
        ByteArrayOutputStream rest = new ByteArrayOutputStream(Math.max(expected * 2, 1024));
        rest.write(bytes);
        rest.write(next);
        inputStream.transferTo(rest);
        return rest.toByteArray();
    }

    /**
     * Transcodes UTF-8 bytes to ISO-8859-1 in place, and returns the resulting length, or -1 when the content has
     * a character that ISO-8859-1 cannot represent or a sequence that is not valid UTF-8. ASCII bytes are left
     * untouched, so an ASCII file costs one scan and nothing else.
     */
    private static int toLatin1(byte[] bytes) {
        int length = bytes.length;
        int out = 0;
        for (int in = 0; in < length; in++) {
            byte b = bytes[in];
            if (b >= 0) {
                if (out != in) {
                    bytes[out] = b;
                }
                out++;
                continue;
            }
            // a two-byte sequence, 110xxxxx 10xxxxxx, encodes U+0080 to U+07FF; ISO-8859-1 stops at U+00FF
            if ((b & 0xE0) != 0xC0 || in + 1 == length || (bytes[in + 1] & 0xC0) != 0x80) {
                return -1;
            }
            int codePoint = (b & 0x1F) << 6 | bytes[in + 1] & 0x3F;
            if (codePoint < 0x80 || codePoint > 0xFF) {
                return -1;
            }
            bytes[out++] = (byte) codePoint;
            in++;
        }
        return out;
    }
}
