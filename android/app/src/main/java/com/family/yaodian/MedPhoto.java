package com.family.yaodian;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 药品照片：从系统选择器复制到应用私有目录，压缩后本地保存。
 *
 * 显示时按需转成 data URL 交给 WebView（避免 file:// 权限问题）。
 */
public final class MedPhoto {

    private static final String TAG = "MedApp";
    private static final int MAX_DIMENSION = 720;
    private static final int JPEG_QUALITY = 82;

    private MedPhoto() {
    }

    private static File dir(Context context) {
        Context storage = context;
        try {
            Context device = context.createDeviceProtectedStorageContext();
            if (device != null) storage = device;
        } catch (Exception error) {
            Log.w(TAG, "device protected storage unavailable for photos", error);
        }
        File dir = new File(storage.getFilesDir(), "med_data/photos");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create photo dir " + dir);
        }
        return dir;
    }

    /** 保存并返回文件名；失败抛异常。 */
    public static String save(Context context, String medId, Uri uri) throws Exception {
        Bitmap bitmap = decode(context, uri);
        if (bitmap == null) throw new Exception("无法读取这张照片");
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int max = Math.max(width, height);
            if (max > MAX_DIMENSION) {
                float scale = MAX_DIMENSION / (float) max;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            String name = medId + "-" + System.currentTimeMillis() + ".jpg";
            File out = new File(dir(context), name);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
                stream.flush();
                stream.getFD().sync();
            }
            return name;
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap decode(Context context, Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source =
                    ImageDecoder.createSource(context.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int max = Math.max(width, height);
                if (max > MAX_DIMENSION * 2) {
                    int sample = 1;
                    while (max / (sample * 2) >= MAX_DIMENSION * 2) sample *= 2;
                    decoder.setTargetSampleSize(sample);
                }
            });
        }
        return MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
    }

    /** 读取为 data URL；文件不存在时返回空串。 */
    public static String readDataUrl(Context context, String name) {
        if (name == null || name.isEmpty()) return "";
        File file = new File(dir(context), name);
        if (!file.exists() || file.length() <= 0) return "";
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (InputStream in = new FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = in.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception error) {
            Log.w(TAG, "read photo failed", error);
            return "";
        }
    }

    public static void delete(Context context, String name) {
        if (name == null || name.isEmpty()) return;
        File file = new File(dir(context), name);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** 清空全部照片文件（例如恢复备份后，旧照片不再有引用）。 */
    public static void deleteAll(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}