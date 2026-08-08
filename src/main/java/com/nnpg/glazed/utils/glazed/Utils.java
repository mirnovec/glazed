package com.nnpg.glazed.utils.glazed;

import org.joml.Vector3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import net.minecraft.world.phys.Vec3;

public final class Utils {

    private static final Logger LOG = LoggerFactory.getLogger("Glazed");

    public static File getCurrentJarPath() throws URISyntaxException {
        // getPath() leaves %20 in there and breaks on any folder with a space
        return Paths.get(Utils.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile();
    }

    // https only. only overwrites the file if the whole download worked
    public static boolean overwriteFile(String urlString, File targetFile) {
        HttpURLConnection connection = null;
        Path temp = null;
        try {
            URI uri = URI.create(urlString);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                LOG.error("Refusing to download over a non-HTTPS URL: {}", uri.getScheme());
                return false;
            }

            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                LOG.error("Download failed with HTTP {}", status);
                return false;
            }

            // temp file first so a failed download cant leave a half written jar
            temp = Files.createTempFile("glazed-download", ".tmp");
            try (InputStream inputStream = connection.getInputStream()) {
                Files.copy(inputStream, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.move(temp, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            return true;

        } catch (Exception e) {
            LOG.error("Error downloading file", e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    public static void copyVector(Vector3d destination, Vec3 source) {
        destination.x = source.x;
        destination.y = source.y;
        destination.z = source.z;
    }

    public static void copyVector(Vector3d destination, Vector3d source) {
        destination.x = source.x;
        destination.y = source.y;
        destination.z = source.z;
    }

    public static Vector3d toVector3d(Vec3 vec3d) {
        return new Vector3d(vec3d.x, vec3d.y, vec3d.z);
    }

    public static Vec3 toVec3d(Vector3d vector3d) {
        return new Vec3(vector3d.x, vector3d.y, vector3d.z);
    }

    public static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    public static Vector3d lerp(Vector3d start, Vector3d end, double progress) {
        return new Vector3d(
            lerp(start.x, end.x, progress),
            lerp(start.y, end.y, progress),
            lerp(start.z, end.z, progress)
        );
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
