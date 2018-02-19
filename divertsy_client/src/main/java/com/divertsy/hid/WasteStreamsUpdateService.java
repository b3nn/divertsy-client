package com.divertsy.hid;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;

/**
 * Created by benn on 2/11/2018.
 */

public class WasteStreamsUpdateService extends Service {

    private static final String TAG = "DIVERTSY WS SRV";

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Starting Bind");
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Starting onStartCommand");
        String url = intent.getStringExtra("URL");
        Log.d(TAG, url);

        new FetchJSONData().execute(url);
        return Service.START_NOT_STICKY;
    }

    private class FetchJSONData extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            // These two need to be declared outside the try/catch
            // so that they can be closed in the finally block.
            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;
            String waste_stream_url = params[0];

            // Will contain the raw JSON response as a string.
            String forecastJsonStr = null;

            try {
                URL url = new URL(waste_stream_url);

                while (true)
                {
                    URL resourceUrl = url;
                    urlConnection= (HttpURLConnection) resourceUrl.openConnection();

                    urlConnection.setConnectTimeout(15000);
                    urlConnection.setReadTimeout(15000);
                    urlConnection.setInstanceFollowRedirects(false);

                    switch (urlConnection.getResponseCode())
                    {
                        case HttpURLConnection.HTTP_MOVED_PERM:
                        case HttpURLConnection.HTTP_MOVED_TEMP:
                            String location = urlConnection.getHeaderField("Location");
                            location = URLDecoder.decode(location, "UTF-8");
                            URL base = url;
                            URL next = new URL(base, location);  // Deal with relative URLs
                            url = new URL(next.toExternalForm());
                            urlConnection.disconnect();
                            continue;
                    }

                    break;
                }

                // Read the input stream into a String
                InputStream inputStream = urlConnection.getInputStream();
                StringBuffer buffer = new StringBuffer();
                if (inputStream == null) {
                    // Nothing to do.
                    return null;
                }
                reader = new BufferedReader(new InputStreamReader(inputStream));

                String line;
                while ((line = reader.readLine()) != null) {
                    // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                    // But it does make debugging a *lot* easier if you print out the completed
                    // buffer for debugging.
                    buffer.append(line + "\n");
                }

                if (buffer.length() == 0) {
                    // Stream was empty.  No point in parsing.
                    return null;
                }
                forecastJsonStr = buffer.toString();
            } catch (IOException e) {
                Log.e("PlaceholderFragment", "Error ", e);
                // If the code didn't successfully get the weather data, there's no point in attemping
                // to parse it.
                return null;
            } finally{
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final IOException e) {
                        Log.e("PlaceholderFragment", "Error closing stream", e);
                    }
                }

                return forecastJsonStr;
            }
        }

        private void writeToFile(String data,Context context) {
            try {
                OutputStreamWriter outputStreamWriter =
                        new OutputStreamWriter(context.openFileOutput(WasteStreams.REMOTE_STREAM_FILE, Context.MODE_PRIVATE));
                outputStreamWriter.write(data);
                outputStreamWriter.close();
                Log.i(TAG, "Data saved to disk");
            }
            catch (IOException e) {
                Log.e("Exception", "File write failed: " + e.toString());
            }
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            writeToFile(s, getApplicationContext());
            Log.i(TAG, s);
        }
    }
}
