package com.so.example.tools;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Vadim Zuev
 * @version 1.0.0
 */
public class BasicImageDownloader {

    private OnImageLoaderListener mImageLoaderListener;
    private Set<String> mUrlsInProgress = new HashSet<>();
    private final String TAG = this.getClass().getSimpleName();

    public BasicImageDownloader(@NonNull OnImageLoaderListener listener) {
        this.mImageLoaderListener = listener;
    }

    /**
     * Interface definition for callbacks to be invoked
     * when the image download status changes.
     */
    public interface OnImageLoaderListener {
        /**
         * Invoked if an error has occurred and thus
         * the download did not complete
         *
         * @param error the occurred error
         */
        void onError(ImageError error);

        /**
         * Invoked every time the progress of the download changes
         *
         * @param percent new status in %
         */
        void onProgressChange(int percent);

        /**
         * Invoked after the image has been successfully downloaded
         *
         * @param result the downloaded image
         */
        void onComplete(Bitmap result);
    }

    /**
     * Downloads the image from the given URL using an {@link AsyncTask}. If a download
     * for the given URL is already in progress this method returns immediately.
     *
     * @param imageUrl        the URL to get the image from
     * @param displayProgress if <b>true</b>, the {@link OnImageLoaderListener#onProgressChange(int)}
     *                        callback will be triggered to notify the caller of the download progress
     */
    public void download(@NonNull final String imageUrl, final boolean displayProgress) {
        if (mUrlsInProgress.contains(imageUrl)) {
            Log.w(TAG, "a download for this url is already running, " +
                    "no further download will be started");
            return;
        }

        new AsyncTask<Void, Integer, Bitmap>() {

            private ImageError error;

            @Override
            protected void onPreExecute() {
                mUrlsInProgress.add(imageUrl);
                Log.d(TAG, "starting download");
            }

            @Override
            protected void onCancelled() {
                mImageLoaderListener.onError(error);
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                mImageLoaderListener.onProgressChange(values[0]);
            }

            @Override
            protected Bitmap doInBackground(Void... params) {
                Bitmap bitmap = null;
                try {
                    URLConnection connection = new URL(imageUrl).openConnection();
                    if (displayProgress) {
                        connection.connect();
                        final int length = connection.getContentLength();
                        if (length <= 0) {
                            error = new ImageError("Invalid content length. The URL is probably not pointing to a file")
                                    .setErrorCode(ImageError.ERROR_INVALID_FILE);
                            this.cancel(true);
                        }
                        InputStream is = new BufferedInputStream(connection.getInputStream(), 8192);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte bytes[] = new byte[8192];
                        int count;
                        long read = 0;
                        while ((count = is.read(bytes)) != -1) {
                            read += count;
                            out.write(bytes, 0, count);
                            publishProgress((int) ((read * 100) / length));
                        }
                        bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
                        out.flush();
                        out.close();
                        is.close();
                    } else {
                        InputStream is = connection.getInputStream();
                        bitmap = BitmapFactory.decodeStream(is);
                        is.close();
                    }
                } catch (Throwable e) {
                    if (!this.isCancelled()) {
                        error = new ImageError(e).setErrorCode(ImageError.ERROR_GENERAL_EXCEPTION);
                        mUrlsInProgress.remove(imageUrl);
                        this.cancel(true);
                    }
                }
                return bitmap;
            }

            @Override
            protected void onPostExecute(Bitmap result) {
                if (result == null) {
                    Log.e(TAG, "factory returned a null result");
                    mImageLoaderListener.onError(new ImageError("downloaded file could not be decoded as bitmap")
                            .setErrorCode(ImageError.ERROR_DECODE_FAILED));
                } else {
                    Log.d(TAG, "download complete, " + result.getByteCount() +
                            " bytes transferred");
                    mImageLoaderListener.onComplete(result);
                }
                mUrlsInProgress.remove(imageUrl);
                System.gc();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Interface definition for callbacks to be invoked when
     * the image save procedure status changes
     */
    public interface OnBitmapSaveListener {
        /**
         * Invoked to notify that the image has been
         * successfully saved
         */
        void onBitmapSaved();

        /**
         * Invoked if an error occurs while saving the image
         *
         * @param error the occurred error
         */
        void onBitmapSaveError(ImageError error);
    }

    /**
     * Tries to write the given Bitmap to device's storage using an {@link AsyncTask}.
     * This method handles common errors and will provide an error message via the
     * {@link OnBitmapSaveListener#onBitmapSaveError(ImageError)} callback in case anything
     * goes wrong.
     *
     * @param imageFile       a File representing the image to be saved
     * @param image           the actual Bitmap to save
     * @param listener        an OnBitmapSaveListener instance
     * @param format          image format. Can be one of the following:<br>
     *                        <ul>
     *                        <li>{@link android.graphics.Bitmap.CompressFormat#PNG}</li>
     *                        <li>{@link android.graphics.Bitmap.CompressFormat#JPEG}</li>
     *                        <li>{@link android.graphics.Bitmap.CompressFormat#WEBP}</li>
     *                        </ul>
     * @param shouldOverwrite whether to overwrite an existing file
     */
    public static void writeToDisk(@NonNull final File imageFile, @NonNull final Bitmap image,
                                   @NonNull final OnBitmapSaveListener listener,
                                   @NonNull final Bitmap.CompressFormat format, boolean shouldOverwrite) {

        if (imageFile.isFile() && imageFile.exists()) {
            if (!shouldOverwrite) {
                listener.onBitmapSaveError(new ImageError("file already exists, " +
                        "write operation cancelled").setErrorCode(ImageError.ERROR_FILE_EXISTS));
                return;
            } else if (!imageFile.delete()) {
                listener.onBitmapSaveError(new ImageError("could not delete existing file, " +
                        "most likely the write permission was denied")
                        .setErrorCode(ImageError.ERROR_PERMISSION_DENIED));
                return;
            }
        }

        if (imageFile.isDirectory()) {
            listener.onBitmapSaveError(new ImageError("the specified path points to a directory, " +
                    "should be a file").setErrorCode(ImageError.ERROR_IS_DIRECTORY));
            return;
        }

        File parent = imageFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            listener.onBitmapSaveError(new ImageError("could not create parent directory")
                    .setErrorCode(ImageError.ERROR_PERMISSION_DENIED));
            return;
        }

        try {
            if (!imageFile.createNewFile()) {
                listener.onBitmapSaveError(new ImageError("could not create file")
                        .setErrorCode(ImageError.ERROR_PERMISSION_DENIED));
                return;
            }
        } catch (IOException e) {
            listener.onBitmapSaveError(new ImageError(e).setErrorCode(ImageError.ERROR_GENERAL_EXCEPTION));
            return;
        }

        new AsyncTask<Void, Void, Void>() {

            private ImageError error;

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    FileOutputStream fos = new FileOutputStream(imageFile);
                    image.compress(format, 100, fos);
                    fos.flush();
                    fos.close();
                } catch (IOException e) {
                    error = new ImageError(e).setErrorCode(ImageError.ERROR_GENERAL_EXCEPTION);
                    this.cancel(true);
                }
                return null;
            }

            @Override
            protected void onCancelled() {
                listener.onBitmapSaveError(error);
            }

            @Override
            protected void onPostExecute(Void result) {
                listener.onBitmapSaved();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }


    /**
     * Represents an error that has occurred while
     * downloading image or writing it to disk. Since
     * this class extends {@code Throwable}, you may get the
     * stack trace from an {@code ImageError} object
     */
    public static final class ImageError extends Throwable {

        private int errorCode;
        /**
         * An exception was thrown during an operation.
         * Check the error message for details.
         */
        public static final int ERROR_GENERAL_EXCEPTION = -1;
        /**
         * The URL does not point to a valid file
         */
        public static final int ERROR_INVALID_FILE = 0;
        /**
         * The downloaded file could not be decoded as bitmap
         */
        public static final int ERROR_DECODE_FAILED = 1;
        /**
         * File already exists on disk and shouldOverwrite == false
         */
        public static final int ERROR_FILE_EXISTS = 2;
        /**
         * Could not complete a file operation, most likely due to permission denial
         */
        public static final int ERROR_PERMISSION_DENIED = 3;
        /**
         * The target file is a directory
         */
        public static final int ERROR_IS_DIRECTORY = 4;


        public ImageError(@NonNull String message) {
            super(message);
        }

        public ImageError(@NonNull Throwable error) {
            super(error.getMessage(), error.getCause());
            this.setStackTrace(error.getStackTrace());
        }

        /**
         * @param code the code for the occurred error
         * @return the same ImageError object
         */
        public ImageError setErrorCode(int code) {
            this.errorCode = code;
            return this;
        }

        /**
         * @return the error code that was previously set
         * by {@link #setErrorCode(int)}
         */
        public int getErrorCode() {
            return errorCode;
        }
    }
}