package io.mosip.registration.update;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.HMACUtils2;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.dto.AuthTokenDTO;
import io.mosip.registration.exception.RegBaseCheckedException;
import org.apache.commons.io.FileUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import io.mosip.registration.constants.RegistrationConstants;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class SoftwareUpdateUtil {

    private static final Logger LOGGER = AppConfig.getLogger(SoftwareUpdateUtil.class);
    private static final String CONNECTION_TIMEOUT = "mosip.registration.sw.file.download.connection.timeout";
    private static final String READ_TIMEOUT = "mosip.registration.sw.file.download.read.timeout";
    private static final int DEFAULT_CONNECTION_TIMEOUT = 600000;
    private static final int DEFAULT_READ_TIMEOUT = 0;
    private static final String libFolder = "lib/";
    private static final String UNKNOWN_JARS = ".UNKNOWN_JARS";
    private static final String TEMP_DIRECTORY = ".TEMP";

    protected static boolean deleteUnknownJars(Manifest localManifest) throws IOException {
        StringBuilder builder = new StringBuilder();
        File dir = new File(libFolder);
        Objects.requireNonNull(dir.listFiles(), "No files found in libs");
        File[] libraries = dir.listFiles();
        Map<String, Attributes> entries = localManifest.getEntries();
        for (File file : libraries) {
            if(!entries.containsKey(file.getName())) {
                LOGGER.error("Unknown file found {}", file.getName());
                deleteFile(file.getCanonicalPath());
                builder.append(file.getName());
                builder.append("\n");
            }
        }

        byte[] bytes =  builder.toString().trim().getBytes(StandardCharsets.UTF_8);
        if(bytes.length > 0) {
            LOGGER.error("Writing the unknown jar names");
            FileUtils.writeByteArrayToFile(new File(UNKNOWN_JARS), bytes);
            return true;
        }
        return false;
    }

    protected static boolean validateJarChecksum(File file, Attributes entryAttributes) {
        try {
            if(entryAttributes != null) {
                String checkSum = HMACUtils2.digestAsPlainText(Files.readAllBytes(file.toPath()));
                String manifestCheckSum = entryAttributes.getValue(Attributes.Name.CONTENT_TYPE);
                return manifestCheckSum.equals(checkSum);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check the file {} validity", file.getName(), e);
        }
        return false;
    }

    protected static void download(String url, String fileName) throws RegBaseCheckedException {
        LOGGER.info("invoking url : {}", url);
        try {
            File tempDir = new File(TEMP_DIRECTORY);
            if(!tempDir.exists()) { tempDir.mkdirs(); }

            int connectionTimeout = getConnectionTimeout();
            int readTimeout = getReadTimeout();

            URL fileUrl = new URL(url);
            FileUtils.copyURLToFile(fileUrl, new File(TEMP_DIRECTORY + File.separator + fileName),
                    connectionTimeout, readTimeout);
            return;

        } catch (IOException e) {
            LOGGER.error("Failed to download {}", url, e);
        }
        throw new RegBaseCheckedException("REG-BUILD-005", "Failed to download " + url);
    }

    protected static InputStream download(String url) throws RegBaseCheckedException {
        LOGGER.info("invoking url : {}", url);
        try {
            int connectionTimeout = getConnectionTimeout();
            int readTimeout = getReadTimeout();

            final URLConnection connection = new URL(url).openConnection();
            connection.setConnectTimeout(connectionTimeout);
            connection.setReadTimeout(readTimeout);
            return connection.getInputStream();

        } catch (IOException e) {
            LOGGER.error("Failed to download {}", url, e);
        }
        throw new RegBaseCheckedException("REG-BUILD-005", "Failed to download " + url);
    }

    protected static void downloadZipfile(String url, File destinationFile) throws RegBaseCheckedException {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(getConnectionTimeout());
            factory.setReadTimeout(getReadTimeout());

            RestTemplate restTemplate = new RestTemplate(factory);

            restTemplate.execute(url, HttpMethod.GET,
                    request -> {
                        try {
                            String token = null;
                            AuthTokenDTO authTokenDTO = null;
                            Map<String, Object> appMap = ApplicationContext.map();

                            if (appMap != null) {
                                for (Object value : appMap.values()) {
                                    if (value instanceof String && ((String) value).startsWith("eyJ")) {
                                        token = (String) value;
                                        break;
                                    }
                                }
                            }

                            if (token == null) {
                                authTokenDTO = ApplicationContext.authTokenDTO();
                                if (authTokenDTO == null || authTokenDTO.getCookie() == null) {
                                    authTokenDTO = SessionContext.authTokenDTO();
                                }
                                if (authTokenDTO != null && authTokenDTO.getCookie() != null) {
                                    String cookie = authTokenDTO.getCookie();
                                    if (cookie.startsWith("Authorization=")) {
                                        token = cookie.substring("Authorization=".length());
                                    } else if (cookie.startsWith("Bearer ")) {
                                        token = cookie.substring("Bearer ".length());
                                    } else {
                                        token = cookie;
                                    }
                                }
                            }

                            if (token != null) {
                                LOGGER.info("SUCCESS: Valid JWT Token found. Attaching to request.");
                                request.getHeaders().set(RegistrationConstants.AUTH_AUTHORIZATION, "Bearer " + token);
                                if (authTokenDTO != null && authTokenDTO.getCookie() != null) {
                                    request.getHeaders().set(RegistrationConstants.COOKIE, authTokenDTO.getCookie());
                                }
                            } else {
                                LOGGER.error("CRITICAL: No valid auth token found in ApplicationContext or SessionContext.");
                            }
                        } catch (Exception e) {
                            LOGGER.error("Error in token hunt logic", e);
                        }
                    },
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IOException("Server returned " + response.getStatusCode().value());
                        }
                        FileUtils.copyInputStreamToFile(response.getBody(), destinationFile);
                        return null;
                    });

        } catch (Exception e) {
            LOGGER.error("Failed to download {}", url, e);
            throw new RegBaseCheckedException("REG-BUILD-005", "Failed to download " + url);
        }
    }

    private static int getConnectionTimeout() {
        Integer connectionTimeout = ApplicationContext.getIntValueFromApplicationMap(CONNECTION_TIMEOUT);
        return connectionTimeout == null ? DEFAULT_CONNECTION_TIMEOUT : connectionTimeout;
    }

    private static int getReadTimeout() {
        Integer readTimeout = ApplicationContext.getIntValueFromApplicationMap(READ_TIMEOUT);
        return readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
    }

    protected static boolean deleteFile(String filePath) {
        LOGGER.info("Deleting file {}", filePath);
        Path path = Path.of(filePath);
        try {
            FileUtils.forceDelete(path.toFile());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to delete file {}", path, e);
            deleteFileOnExit(filePath);
        }
        return false;
    }

    protected static void deleteFileOnExit(String filePath) {
        LOGGER.info("Deleting file {}", filePath);
        Path path = Path.of(filePath);
        try {
            FileUtils.forceDeleteOnExit(path.toFile());
        } catch (Exception e) {
            LOGGER.error("Failed to delete file on exit {}", path, e);
        }
    }

    protected static void clearTempDirectory() {
        try {
            File dir = new File(TEMP_DIRECTORY);
            if(dir.exists()) {
                FileUtils.cleanDirectory(dir);
                if (dir.delete())
                    LOGGER.info("Deleted temp file");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to clean and delete temp", e);
        }
    }

    /*private static boolean hasSpace(int bytes) throws RegBaseCheckedException {
        boolean hasSpace = bytes < new File(File.separator).getFreeSpace();
        if(!hasSpace)
            throw new RegBaseCheckedException("REG-BUILD-004", "Not enough space available");
        return true;
    }*/
}

