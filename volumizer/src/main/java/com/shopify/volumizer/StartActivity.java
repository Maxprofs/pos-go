package com.shopify.volumizer;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.atap.tangoservice.Tango;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;
import toothpick.Scope;
import toothpick.Toothpick;

/**
 * Start Activity for Area Description example. Gives the ability to choose a particular
 * configuration and also Manage Area Description Files (ADF).
 */
public class StartActivity extends Activity {
    // The unique key string for storing user's input.
    public static final String USE_AREA_LEARNING = "com.shopify.posgo.usearealearning";
    public static final String LOAD_ADF = "com.shopify.posgo.loadadf";

    // Permission request action.
    public static final int REQUEST_CODE_TANGO_PERMISSION = 0;

    public static final int REQUEST_CAMERA_PERM = 1;


    // UI elements.
    @BindView(R.id.learningModeToggleButton)
    ToggleButton learningModeToggleButton;
    @BindView(R.id.loadAdfToggleButton)
    ToggleButton loadAdfToggleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Timber.i("onCreate()");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_start);
        ButterKnife.bind(this);

        setTitle(R.string.app_name);

        // Ask permission to load ADF files
        startActivityForResult(Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_ADF_LOAD_SAVE), 0);
        requestPermissions();
    }

    @OnClick(R.id.startButton)
    public void startClicked(View v) {
        startMainActivity();
    }

    /**
     * Start the main area description activity and pass in user's configuration.
     */
    private void startMainActivity() {
        Intent startAdIntent = new Intent(this, VolumizerActivity.class);
        startAdIntent.putExtra(USE_AREA_LEARNING, learningModeToggleButton.isChecked());
        startAdIntent.putExtra(LOAD_ADF, loadAdfToggleButton.isChecked());
        startActivity(startAdIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Timber.i("onActivityResult()");
        // The result of the permission activity.
        //
        // Know that when the permission activity is dismissed, the HelloAreaDescriptionActivity's
        // onResume() callback is called. As the TangoService is connected in the onResume()
        // function, we do not call connect here.
        //
        // Check which request we're responding to
        if (requestCode == REQUEST_CODE_TANGO_PERMISSION) {
            // Make sure the request was successful
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, R.string.arealearning_permission, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    void requestPermissions() {
        Timber.i("requestPermissions()");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {

                // Show an expanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                Timber.i("Show an expanation to the user *asynchronously*");

            } else {
                // No explanation needed, we can request the permission.
                Timber.i("No explanation needed, we can request the permission.");

                ActivityCompat.requestPermissions(
                        this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERM);

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        Timber.i("onRequestPermissionsResult()");
        switch (requestCode) {
            case REQUEST_CAMERA_PERM:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    Timber.i("permission was granted, yay!");

                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Timber.i("permission denied, boo!");
                }
                break;
            default:
                // other 'case' lines to check for other
                // permissions this app might request
                Timber.i("other 'case'??");
                break;
        }

    }

}
