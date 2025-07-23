package com.dan.eo1;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.squareup.picasso.Picasso;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

   public int interval = 5;
   private int currentPosition = 0;
   private Handler handler = new Handler();
   private ImageView imageView;
   private View overlayView;
   private VideoView videoView;
   private String serviceAccountEmail = "";
   private String serviceAccountPrivateKey = "";
   private String folderId = "";
   private GoogleDriveHelper driveHelper;
   private int displayOption = 0;
   private int startQuietHour = -1;
   private int endQuietHour = -1;
   private List<String> photoList;
   private boolean isInQuietHours = false;
   private SensorManager mSensorManager;
   private Sensor mLightSensor;
   private float lastLightLevel;
   private boolean slideshowpaused = false;
   private ProgressBar progress;
   boolean screenon = true;
   boolean autobrightness = true;
   float brightnesslevel = 0.5f;
   int page = 1;
   int totalPages = 0;
   int curmediatype = -1;
   int FADEDURATION = 5000; //2000;
   int lastmediatype = -1;

   @Override
   protected void onCreate(Bundle savedInstanceState) {
       setContentView(R.layout.activity_main);

       File cacheDir = new File(getCacheDir(), "picasso-cache");
       if (cacheDir.exists() && cacheDir.isDirectory()) {
           for (File file : cacheDir.listFiles()) {
               file.delete();
           }
       }

       loadsettings();

       if (serviceAccountEmail.isEmpty() || serviceAccountPrivateKey.isEmpty() || folderId.isEmpty()) {
           showSetupDialog();
       }

       imageView = findViewById(R.id.imageView);
       videoView = findViewById(R.id.videoView);
       progress = findViewById(R.id.progressBar);
       overlayView = findViewById(R.id.black_overlay);

       mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
       mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
       SensorEventListener listener = new SensorEventListener() {
           @Override
           public void onSensorChanged(SensorEvent event) {
               if (Math.abs(event.values[0] - lastLightLevel) >= 10.0f) {
                   adjustScreenBrightness(event.values[0]);
               }
           }
           @Override
           public void onAccuracyChanged(Sensor sensor, int i) {
           }
       };
       mSensorManager.registerListener(listener, mLightSensor, SensorManager.SENSOR_DELAY_UI);

       LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, new IntentFilter("MSG_RECEIVED"));

       if (quietHoursCalc()) {
           isInQuietHours = true;
           WindowManager.LayoutParams params = getWindow().getAttributes();
           params.screenBrightness = 0;
           getWindow().setAttributes(params);
           videoView.setVisibility(View.GONE);
           imageView.setVisibility(View.GONE);
       }

       super.onCreate(savedInstanceState);
   }

   boolean quietHoursCalc() {
       Calendar calendar = Calendar.getInstance();
       int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
       int normalizedStart = (startQuietHour + 24) % 24;
       int normalizedEnd = (endQuietHour + 24) % 24;
       if ((currentHour >= normalizedStart && currentHour < normalizedEnd) ||
               (normalizedStart > normalizedEnd && (currentHour >= normalizedStart || currentHour < normalizedEnd))) {
           return true;
       } else {
           return false;
       }
   }

   void loadsettings() {
       SharedPreferences settings = getSharedPreferences("prefs", MODE_PRIVATE);
       serviceAccountEmail = settings.getString("serviceAccountEmail", "");
       serviceAccountPrivateKey = settings.getString("serviceAccountPrivateKey", "");
       folderId = settings.getString("folderId", "");

       displayOption = settings.getInt("displayOption", 0);
       startQuietHour = settings.getInt("startQuietHour", -1);
       endQuietHour = settings.getInt("endQuietHour", -1);
       interval = settings.getInt("interval", 5);
       autobrightness = settings.getBoolean("autobrightness", true);
       brightnesslevel = settings.getFloat("brightnesslevel", 0.5f);
   }

   @Override
   protected void onResume() {
       if (!serviceAccountEmail.isEmpty() && !serviceAccountPrivateKey.isEmpty() && !folderId.isEmpty()) {
           loadImagesFromDataSource();
       }

       super.onResume();
   }

   @Override
   protected void onPause() {
       super.onPause();
       handler.removeCallbacksAndMessages(null);
   }

   @SuppressLint("InvalidWakeLockTag")
   @Override
   public boolean onKeyDown(int keyCode, KeyEvent event) {
       if (keyCode == KeyEvent.KEYCODE_C) {
           showSetupDialog();
           return super.onKeyDown(keyCode, event);
       }

       if (keyCode == KeyEvent.KEYCODE_Q) {
       }

       if (keyCode == KeyEvent.KEYCODE_SPACE) {
           doResume();
           return super.onKeyDown(keyCode, event);
       }

       if (keyCode == 132 || keyCode == 134) {
           //top button pushed
           WindowManager.LayoutParams params = getWindow().getAttributes();
           if (screenon) {
               params.screenBrightness = 0;
               screenon = false;
               imageView.setVisibility(View.INVISIBLE);
               videoView.setVisibility(View.INVISIBLE);
           } else {
               params.screenBrightness = brightnesslevel;
               screenon = true;
               imageView.setVisibility(View.VISIBLE);
               videoView.setVisibility(View.VISIBLE);
           }
           getWindow().setAttributes(params);
           return super.onKeyDown(keyCode, event);
       }

       return super.onKeyDown(keyCode, event);
   }

   private void showSetupDialog() {
       AlertDialog.Builder builder = new AlertDialog.Builder(this);
       View customLayout = getLayoutInflater().inflate(R.layout.options, null);
       builder.setView(customLayout);

       final EditText editTextServiceAccountEmail = customLayout.findViewById(R.id.editTextServiceAccountEmail);
       final EditText editTextServiceAccountPrivateKey = customLayout.findViewById(R.id.editTextSericeAccountPrivateKey);
       final EditText editTextFolderId = customLayout.findViewById(R.id.editTextFolderID);
       final Spinner startHourSpinner = customLayout.findViewById(R.id.startHourSpinner);
       final Spinner endHourSpinner = customLayout.findViewById(R.id.endHourSpinner);
       final Button btnLoadConfig = customLayout.findViewById(R.id.btnLoadConfig);
       final EditText editTextInterval = customLayout.findViewById(R.id.editTextInterval);
       final CheckBox cbAutoBrightness = customLayout.findViewById(R.id.cbBrightnessAuto);
       final SeekBar sbBrightness = customLayout.findViewById(R.id.sbBrightness);

       editTextServiceAccountEmail.setText(serviceAccountEmail);
       editTextServiceAccountPrivateKey.setText(serviceAccountPrivateKey);
       editTextFolderId.setText(folderId);

       editTextInterval.setText(String.valueOf(interval));
       if (autobrightness) {
           cbAutoBrightness.setChecked(true);
           sbBrightness.setVisibility(View.GONE);
       }
       RadioGroup optionsRadioGroup = customLayout.findViewById(R.id.optionsRadioGroup);
       sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
           @Override
           public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
               brightnesslevel = i / 10f;
               WindowManager.LayoutParams params = getWindow().getAttributes();
               params.screenBrightness = brightnesslevel;
               getWindow().setAttributes(params);
           }
           @Override
           public void onStartTrackingTouch(SeekBar seekBar) {
           }
           @Override
           public void onStopTrackingTouch(SeekBar seekBar) {
           }
       });
       sbBrightness.setProgress((int) (brightnesslevel * 10));

       cbAutoBrightness.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
           @Override
           public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
               autobrightness = b;
               if (b)
                   sbBrightness.setVisibility(View.GONE);
               else
                   sbBrightness.setVisibility(View.VISIBLE);
           }
       });

       if (displayOption == 0) ((RadioButton) customLayout.findViewById(R.id.radioOption1)).setChecked(true);
       if (displayOption == 3) ((RadioButton) customLayout.findViewById(R.id.radioOption4)).setChecked(true);

       // Set up the Spinners for start and end hour
       String[] hours = new String[24];
       for (int i = 0; i < 24; i++) {
           hours[i] = String.format("%02d", i);
       }
       ArrayAdapter<String> hourAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, hours);
       hourAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
       startHourSpinner.setAdapter(hourAdapter);
       if (startQuietHour != -1) startHourSpinner.setSelection(startQuietHour);
       endHourSpinner.setAdapter(hourAdapter);
       if (endQuietHour != -1) endHourSpinner.setSelection(endQuietHour);

       btnLoadConfig.setOnClickListener(new View.OnClickListener() {
           @Override
           public void onClick(View view) {
               File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
               File file = new File(downloadsDir, "config.json");
               if (!file.exists()) {
                   Toast.makeText(MainActivity.this, "Can't find config.json", Toast.LENGTH_SHORT).show();
               } else {
                   StringBuilder sb = new StringBuilder();
                   try {
                       BufferedReader br = new BufferedReader(new FileReader(file));
                       String line;
                       while ((line = br.readLine()) != null) {
                           sb.append(line);
                       }
                       br.close();
                   } catch (IOException e) {
                       e.printStackTrace();
                   }

                   try {
                       JSONObject jsonObject = new JSONObject(sb.toString());
                       serviceAccountEmail = jsonObject.getString("client_email");
                       serviceAccountPrivateKey = jsonObject.getString("private_key");
                       folderId = jsonObject.getString("folder_id");

                       editTextServiceAccountEmail.setText(serviceAccountEmail);
                       editTextServiceAccountPrivateKey.setText(serviceAccountPrivateKey);
                       editTextFolderId.setText(folderId);
                   } catch (JSONException e) {
                       throw new RuntimeException(e);
                   }
               }
           }
       });

       builder.setTitle("Setup")
               .setCancelable(false)
               .setView(customLayout)
               .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                   @Override
                   public void onClick(DialogInterface dialog, int which) {
                       serviceAccountEmail = editTextServiceAccountEmail.getText().toString().trim();
                       serviceAccountPrivateKey = editTextServiceAccountPrivateKey.getText().toString().trim();
                       folderId = editTextFolderId.getText().toString().trim();
                       displayOption = Util.getSelectedOptionIndex(optionsRadioGroup);
                       startQuietHour = Integer.parseInt(startHourSpinner.getSelectedItem().toString());
                       endQuietHour = Integer.parseInt(endHourSpinner.getSelectedItem().toString());
                       interval = Integer.parseInt(editTextInterval.getText().toString().trim());
                       autobrightness = cbAutoBrightness.isChecked();

                       saveOptions();
                   }
               })
               .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                   @Override
                   public void onClick(DialogInterface dialog, int which) {
                       dialog.dismiss();
                   }
               });

       builder.show();
   }

   private void saveOptions() {
       if (!serviceAccountEmail.isEmpty() && !serviceAccountPrivateKey.isEmpty() && !folderId.isEmpty()) {
           SharedPreferences settings = getSharedPreferences("prefs", MODE_PRIVATE);
           SharedPreferences.Editor editor = settings.edit();
           editor.putString("serviceAccountEmail", serviceAccountEmail);
           editor.putString("serviceAccountPrivateKey", serviceAccountPrivateKey);
           editor.putString("folderId", folderId);
           editor.putInt("displayOption", displayOption);
           editor.putInt("startQuietHour", startQuietHour);
           editor.putInt("endQuietHour", endQuietHour);
           editor.putInt("interval", interval);
           editor.putBoolean("autobrightness", autobrightness);
           editor.putFloat("brightnesslevel", brightnesslevel);
           editor.apply();

           Toast.makeText(MainActivity.this, "Saved!  Hit 'C' to come back here later.", Toast.LENGTH_SHORT).show();

           if (quietHoursCalc()) isInQuietHours = true; else isInQuietHours = false;

           loadImagesFromDataSource();

           if (isInQuietHours) adjustScreenBrightness(0);
       } else {
           Toast.makeText(MainActivity.this, "Please enter User ID and API Key", Toast.LENGTH_SHORT).show();
       }
   }

   private void startSlideshow() {
       handler.removeCallbacksAndMessages(null);
       handler.postDelayed(new Runnable() {
           @Override
           public void run() {
               overlayView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
               overlayView.setVisibility(View.VISIBLE);
               overlayView.setAlpha(0.0f); // Set initial state
               overlayView.setBackgroundColor(Color.BLACK);
               ObjectAnimator fadeOut  = ObjectAnimator.ofFloat(overlayView, "alpha", 0f, 1f);
               fadeOut.setDuration(FADEDURATION);
               fadeOut.addListener(new AnimatorListenerAdapter() {
                   @Override
                   public void onAnimationStart(Animator animation) {}
                   @Override
                   public void onAnimationEnd(Animator animation) {
                       showSlideshow();
                   }
                   @Override
                   public void onAnimationRepeat(Animator animation) {}
               });
               fadeOut.start();

               handler.postDelayed(this, 60000 * interval);
           }
       }, 60000 * interval);

       showSlideshow();
   }

   private void doneLoading() {
       progress.setVisibility(View.INVISIBLE);

       overlayView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
       overlayView.setVisibility(View.VISIBLE);
       overlayView.setAlpha(1.0f); // Set initial state
       overlayView.setBackgroundColor(Color.BLACK);

       if (curmediatype == 1) {
           //image
           imageView.setVisibility(View.VISIBLE);
           videoView.setVisibility(View.INVISIBLE);
       } else {
           //video
           imageView.setVisibility(View.INVISIBLE);
           videoView.setVisibility(View.VISIBLE);
       }

       ObjectAnimator fadeIn  = ObjectAnimator.ofFloat(overlayView, "alpha", 1f, 0f);
       fadeIn.setDuration(FADEDURATION);
       fadeIn.addListener(new AnimatorListenerAdapter() {
           @Override
           public void onAnimationStart(Animator animation) {}
           @Override
           public void onAnimationEnd(Animator animation) {
               overlayView.setAlpha(0f);
               overlayView.setVisibility(View.GONE);
           }
           @Override
           public void onAnimationRepeat(Animator animation) {}
       });
       fadeIn.start();
   }

   private void showSlideshow() {
       if (quietHoursCalc()) {
           if (!isInQuietHours) {
               //entering quiet, turn off screen
               isInQuietHours = true;
               WindowManager.LayoutParams params = getWindow().getAttributes();
               params.screenBrightness = 0;
               getWindow().setAttributes(params);
               videoView.setVisibility(View.GONE);
               imageView.setVisibility(View.GONE);
           }
       } else {
           if (isInQuietHours) {
               isInQuietHours = false;
           }
           if (autobrightness) {
               adjustScreenBrightness(lastLightLevel);
           } else {
               WindowManager.LayoutParams params = getWindow().getAttributes();
               params.screenBrightness = brightnesslevel;
               getWindow().setAttributes(params);
           }
           doResume();
       }
   }

   private void showNextMedia() {
       if (photoList != null && !photoList.isEmpty() && slideshowpaused==false) {
           if (currentPosition >= photoList.size()) {
               if (page + 1 <= totalPages) page++;
               loadImagesFromDataSource();
               if (page == totalPages) page = 1;
               return;
           }

           try {
               String url = photoList.get(currentPosition);
               if (!url.endsWith(".mp4")) {
                   showNextImg();

               } else {
                   lastmediatype = curmediatype;

                   curmediatype = 0;

                   MediaController mediaController = new MediaController(this);
                   mediaController.setAnchorView(videoView);
                   mediaController.setVisibility(View.INVISIBLE);

                   videoView.setMediaController(mediaController);
                   videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                       @Override
                       public void onPrepared(MediaPlayer mediaPlayer) {
                           mediaPlayer.setLooping(true);
                       }
                   });

                   if (displayOption == 3) {
                       //private video option
                       videoView.setVideoPath(photoList.get(currentPosition));
                       showVid();
                   } else {
                       new DownloadVideoTask().execute(photoList.get(currentPosition), String.valueOf(currentPosition));
                   }

               }
           } catch (Exception ex) {
               progress.setVisibility(View.VISIBLE);
               new android.os.Handler().postDelayed(new Runnable() {
                   @Override
                   public void run() {
                       showNextMedia();
                   }
               }, 10000);
           }
       }
   }

   @SuppressLint("StaticFieldLeak")
   private void showNextImg() {
       curmediatype = 1;
       if (displayOption == 3) {
           //private images
           Picasso.get().load(Uri.parse("file://" + photoList.get(currentPosition))).fit().centerInside().into(imageView);
           currentPosition++;
           doneLoading();
       } else {
           String imageId = photoList.get(currentPosition).split("\\|")[0];
           new AsyncTask<Void, Void, File>() {
               @Override
               protected File doInBackground(Void... voids) {
                   try (InputStream fileStream = driveHelper.downloadDriveFile(imageId)) {
                       if (fileStream != null) {
                           File tempFile = new File(getCacheDir(), imageId + ".image");

                           OutputStream outputStream = new FileOutputStream(tempFile);

                           byte[] buffer = new byte[8192];
                           int bytesRead;
                           while ((bytesRead = fileStream.read(buffer)) != -1) {
                               outputStream.write(buffer, 0, bytesRead);
                           }

                           outputStream.flush();
                           outputStream.close();

                           return tempFile;
                       }
                   } catch (IOException e) {
                       throw new RuntimeException(e);
                   } catch (Exception e) {
                       throw new RuntimeException(e);
                   }
                   return null;
               }

               protected void onPostExecute(File tempFile) {
                   Picasso.get().load(Uri.fromFile(tempFile)).fit().centerInside().into(imageView, new com.squareup.picasso.Callback() {
                       @Override
                       public void onSuccess() {
                           currentPosition++;
                           doneLoading();
                       }

                       @Override
                       public void onError(Exception e) {
                           e.printStackTrace();
                       }
                   });
               }
           }.execute();
       }
   }

   @SuppressLint("StaticFieldLeak")
   private void loadImagesFromDataSource() {
       if (!isInQuietHours) progress.setVisibility(View.VISIBLE);
       imageView.setImageDrawable(null);
       videoView.setVideoURI(null);
       imageView.setVisibility(View.INVISIBLE);
       videoView.setVisibility(View.INVISIBLE);

       if (Util.isNetworkAvailable(this)) {
           Toast.makeText(MainActivity.this, "IP = " + Util.getIPAddress(), Toast.LENGTH_LONG).show();

           if (displayOption == 3) {
               //load all images from /images folder in cache
               currentPosition = 0;
               photoList = new ArrayList<>();
               File imagesFolder = new File(this.getCacheDir(), "images");
               if (imagesFolder.exists() && imagesFolder.isDirectory()) {
                   File[] files = imagesFolder.listFiles();
                   if (files != null) {
                       for (File file : files) {
                           photoList.add(file.getAbsolutePath());
                       }
                   }
               }
               Collections.shuffle(photoList, new Random());
               startSlideshow();

           } else {
               new AsyncTask<Void, Void, List<String>>() {
                   @Override
                   protected List<String> doInBackground(Void... voids) {
                       driveHelper = new GoogleDriveHelper(serviceAccountEmail, serviceAccountPrivateKey);
                       List<String> files = null;
                       try {
                           files = driveHelper.listFiles(folderId);
                           Collections.shuffle(files, new Random());
                       } catch (Exception e) {
                           throw new RuntimeException(e);
                       }
                       return files;
                   }

                   protected void onPostExecute(List<String> urls) {
                       photoList = urls;
                       currentPosition = 0;
                       startSlideshow();
                   }
               }.execute();
           }
       } else {
           new android.os.Handler().postDelayed(new Runnable() {
               @Override
               public void run() {
                   loadImagesFromDataSource(); // Retry loading images after delay
               }
           }, 10000);
       }
   }

   private void adjustScreenBrightness(float lightValue){
       if (autobrightness) {
           if (!isInQuietHours) {
               // Determine the desired brightness range
               float maxBrightness = 1.0f; // Maximum brightness value (0 to 1)
               float minBrightness = 0.0f; // Minimum brightness value (0 to 1)

               // Map the light sensor value (0 to 30) to the desired brightness range (0 to 1)
               float brightness = (lightValue / 30f) * (maxBrightness - minBrightness) + minBrightness;

               // Make sure brightness is within the valid range
               brightness = Math.min(Math.max(brightness, minBrightness), maxBrightness);

               // Apply the brightness setting to the screen
               WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
               layoutParams.screenBrightness = brightness;
               getWindow().setAttributes(layoutParams);
           }
       }
       lastLightLevel = lightValue;
   }

   private class DownloadVideoTask extends AsyncTask<String, Void, String> {
       private static final int CONNECTION_TIMEOUT = 15000;
       private static final int MAX_RETRIES = 3;
       @Override
       protected String doInBackground(String... params) {
           String[] videoInfo = params[0].split("\\|");
           String videoId = videoInfo[0];
           if (new File(getCacheDir(), videoId + ".mp4").exists()) {
               return new File(getCacheDir(), videoId + ".mp4").getPath();
           }
           Util.cacheCleanup(getCacheDir());
           try {
               try (InputStream fileStream = driveHelper.downloadDriveFile(videoId)) {
                    if (fileStream != null) {
                        File tempFile = new File(getCacheDir(), videoId + ".mp4");
                        OutputStream outputStream = new FileOutputStream(tempFile);

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fileStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }

                        outputStream.flush();
                        outputStream.close();

                        return tempFile.getPath();
                    } else {
                        throw new RuntimeException();
                    }
               }

           } catch (SocketTimeoutException e) {
               Toast.makeText(MainActivity.this, "SocketTimeoutException> " + e.getMessage(), Toast.LENGTH_LONG).show();
           } catch (IOException e) {
               Toast.makeText(MainActivity.this, "IOException> " + e.getMessage(), Toast.LENGTH_LONG).show();
           } catch (Exception e) {
               Toast.makeText(MainActivity.this, "Exception> " + e.getMessage(), Toast.LENGTH_LONG).show();
           }

           return "ERR: Timeout";
       }

       @Override
       protected void onPostExecute(String file) {
           if (!file.startsWith("ERR")) {
               videoView.setVideoPath(file);
               showVid();
           } else {
               Toast.makeText(MainActivity.this, "ERR> " + file, Toast.LENGTH_LONG).show();
           }
       }
   }

   private void showVid() {
       currentPosition++;
       videoView.start();
       doneLoading();
   }

   private BroadcastReceiver messageReceiver = new BroadcastReceiver() {
       @Override
        public void onReceive(Context context, Intent intent) {
           if (intent != null && intent.getAction() != null) {
               if (intent.getAction().equals("MSG_RECEIVED")) {
                   String type = intent.getStringExtra("type");

                   if (type.equals("options")) {
                       WindowManager.LayoutParams params = getWindow().getAttributes();
                       float incomingbrightness = intent.getFloatExtra("brightness", 1f);
                       if (incomingbrightness == -1.0f) {
                           autobrightness = true;
                           adjustScreenBrightness(lastLightLevel);
                       } else {
                           autobrightness = false;
                           brightnesslevel = incomingbrightness;
                           params.screenBrightness = incomingbrightness;
                           getWindow().setAttributes(params);
                       }

                       startQuietHour = intent.getIntExtra("startQuietHour", -1);
                       endQuietHour = intent.getIntExtra("endQuietHour", -1);
                       interval = intent.getIntExtra("interval", 5);
                       displayOption = intent.getIntExtra("displayOption" , -1);

                       saveOptions();
                   }

                   if (type.equals("image") || type.equals("video")) {
                   }

                   if (type.equals("resume")) {
                       doResume();
                   }

                   if (type.equals("rawimage")) {
                       progress.setVisibility(View.VISIBLE);
                       imageView.setVisibility(View.INVISIBLE);
                       videoView.setVisibility(View.INVISIBLE);

                       slideshowpaused = true;

                       if (isInQuietHours) {
                           isInQuietHours = false;
                           WindowManager.LayoutParams params = getWindow().getAttributes();
                           params.screenBrightness = brightnesslevel;
                           getWindow().setAttributes(params);
                       }

                       byte[] decodedBytes = Base64.decode(intent.getStringExtra("image"), Base64.DEFAULT);
                       File tempFile = null;
                       FileOutputStream out = null;
                       try {
                           File imagesFolder = new File(context.getCacheDir(), "images");
                           if (!imagesFolder.exists() && !imagesFolder.mkdirs()) {}
                           tempFile = File.createTempFile(intent.getStringExtra("filename"), "", imagesFolder);
                           out = new FileOutputStream(tempFile);
                           out.write(decodedBytes);
                       } catch (IOException e) {
                           e.printStackTrace();
                       } finally {
                           if (out != null) {
                               try {
                                   out.close();
                               } catch (IOException e) {
                                   e.printStackTrace();
                               }
                           }
                       }

                       videoView.setVisibility(View.INVISIBLE);

                       Picasso.get().load(Uri.fromFile(tempFile)).fit().centerInside().into(imageView);
                       progress.setVisibility(View.INVISIBLE);
                       imageView.setVisibility(View.VISIBLE);
                   }

                   if (type.equals("clear")) {
                       File imagesFolder = new File(getApplicationContext().getCacheDir(), "images");
                       if (imagesFolder.exists() && imagesFolder.isDirectory()) {
                           File[] files = imagesFolder.listFiles();
                           if (files != null) {
                               for (File file : files) {
                                   file.delete();
                               }
                           }
                       }

                       Toast.makeText(MainActivity.this, "Private items cleared", Toast.LENGTH_SHORT).show();
                   }
               }
           }
       }
   };

   private void doResume() {
       slideshowpaused = false;
       showNextMedia();
   }
}

class GoogleDriveHelper {
    private static final String TAG = "GoogleDriveHelper";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String DRIVE_API_FILES_URL = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly";

    private final String serviceAccountEmail;
    private final String serviceAccountPrivateKey;
    private final OkHttpClient httpClient;

    private String accessToken;
    private long tokenExpirationTime;

    public GoogleDriveHelper(String serviceAccountEmail, String serviceAccountPrivateKey) {
        this.serviceAccountEmail = serviceAccountEmail;
        this.serviceAccountPrivateKey = serviceAccountPrivateKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private String getAccessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < tokenExpirationTime) {
            return accessToken;
        }
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        Date expiry = new Date(nowMillis + TimeUnit.HOURS.toMillis(1));
        PrivateKey privateKey = getPrivateKeyFromString(serviceAccountPrivateKey);
        String signedJwt = Jwts.builder()
                .setHeaderParam("alg", "RS256")
                .setHeaderParam("typ", "JWT")
                .setIssuer(serviceAccountEmail)
                .setAudience(TOKEN_URL)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .claim("scope", DRIVE_SCOPE)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
        RequestBody requestBody = new FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", signedJwt)
                .build();
        Request request = new Request.Builder().url(TOKEN_URL).post(requestBody).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get access token: " + response.body().string());
            }
            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);
            accessToken = json.getString("access_token");
            int expiresIn = json.getInt("expires_in");
            tokenExpirationTime = System.currentTimeMillis() + (expiresIn - 60) * 1000L;

            return accessToken;
        }
    }

    @Nullable
    public List<String> listFiles(String folderId) throws Exception {
        String token = getAccessToken();
        String url = DRIVE_API_FILES_URL + "?q='" + folderId + "' in parents and trashed=false"
                + "&fields=files(id,name)&pageSize=1000";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            JSONObject json = new JSONObject(response.body().string());
            JSONArray filesArray = json.getJSONArray("files");
            ArrayList<String> files = new ArrayList<>();
            for (int i = 0; i < filesArray.length(); i++) {
                JSONObject fileObject = filesArray.getJSONObject(i);
                files.add(fileObject.getString("id") + "|" + fileObject.getString("name"));
            }
            return files;
        }
    }

    @Nullable
    public InputStream downloadDriveFile(String fileId) throws Exception {
        String token = getAccessToken();
        String url = DRIVE_API_FILES_URL + "/" + fileId + "?alt=media";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            return null;
        }
        ResponseBody body = response.body();
        return body != null ? body.byteStream() : null;
    }

    private PrivateKey getPrivateKeyFromString(String key) throws Exception {
        String privateKeyPEM = key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\\\n", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.decode(privateKeyPEM, Base64.DEFAULT);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        return keyFactory.generatePrivate(keySpec);
    }

}