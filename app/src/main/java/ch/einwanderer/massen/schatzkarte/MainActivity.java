package ch.einwanderer.massen.schatzkarte;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.telecom.Connection;
import android.view.View;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.DefaultResourceProxyImpl;
import org.osmdroid.ResourceProxy;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.MapTileProviderArray;
import org.osmdroid.tileprovider.MapTileProviderBase;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.MBTilesFileArchive;
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private MapView map;
    private LocationManager locationManager;
    private String provider;
    private IMapController controller;
    private GeoPoint currentLocation;
    private MyItemizedOverlay overlay;
    private SharedPreferences prefs;

    private final String MARKER_PREF = "marker_pref_schatzkarte";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!isOnline()) {
            Toast.makeText(this, "No internet connection!", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs = getSharedPreferences("ch.einwanderer.massen.schatzkarte", Context.MODE_PRIVATE);

        setUpMap();
        setUpGPS();
        setUpMarkers();
        addEventListeners();
    }

    private List<GeoPoint> getMarkerPrefs() {
        Set<String> markerStrings = prefs.getStringSet(MARKER_PREF, new HashSet<String>());
        List<GeoPoint> markers = new ArrayList<>();
        for(String markerString : markerStrings) {
            String[] markerPos = markerString.split(";");
            markers.add(new GeoPoint(Double.parseDouble(markerPos[0]), Double.parseDouble(markerPos[1])));
        }
        return markers;
    }

    private void deleteMarkerPrefs() {
        prefs.edit().putStringSet(MARKER_PREF, Collections.<String>emptySet()).commit();
    }

    private void addMarkerPref(GeoPoint marker) {
        Set<String> markerStrings = prefs.getStringSet(MARKER_PREF, new HashSet<String>());
        markerStrings.add(Double.toString(marker.getLatitude()) + ';' + Double.toString(marker.getLongitude()));
        prefs.edit().putStringSet(MARKER_PREF, markerStrings).commit();
    }

    private void addEventListeners() {
        findViewById(R.id.log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logSolution();
            }
        });
        findViewById(R.id.mark).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMarker();
            }
        });
        findViewById(R.id.hsr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gotoHSR();
            }
        });
        findViewById(R.id.delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMarkers();
            }
        });
        findViewById(R.id.location).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gotoCurrent();
            }
        });
    }

    private void logSolution() {
        Intent logIntent = new Intent("ch.appquest.intent.LOG");

        if (getPackageManager().queryIntentActivities(logIntent, PackageManager.MATCH_ALL).isEmpty()) {
            Toast.makeText(this, "Logbook App not Installed", Toast.LENGTH_LONG).show();
            return;
        }

        JSONObject jsonLog = new JSONObject();
        try {
            jsonLog.put("task", "Schatzkarte");
            List<GeoPoint> markers = getMarkerPrefs();
            JSONArray jsonPoints = new JSONArray();
            for (GeoPoint marker: markers) {
                JSONObject jsonPoint = new JSONObject();
                jsonPoint.put("lat", marker.getLatitudeE6());
                jsonPoint.put("lon", marker.getLongitudeE6());
                jsonPoints.put(jsonPoint);
            }
            jsonLog.put("points", jsonPoints);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        logIntent.putExtra("ch.appquest.logmessage", jsonLog.toString());
        startActivity(logIntent);
    }

    private void setMarker() {
        addMarkerPref(currentLocation);
        displayMarker(currentLocation);
    }

    private void displayMarker(GeoPoint marker) {
        overlay.addItem(marker, "Posten", "Posten");
    }

    private void gotoHSR() {
        controller.setCenter(new GeoPoint(47.2253188, 8.8150867));
    }

    private void gotoCurrent() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null);
        if (currentLocation != null) {
            controller.setCenter(currentLocation);
        } else {
            Location loc = locationManager.getLastKnownLocation(provider);
            if (loc != null) {
                currentLocation = new GeoPoint(loc);
                controller.setCenter(currentLocation);
            }
        }
    }

    private void deleteMarkers() {
        deleteMarkerPrefs();
        overlay.clear();
    }

    private void setUpMap() {
        map = (MapView) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPQUESTOSM);

        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(true);

        controller = map.getController();
        controller.setZoom(19);
        gotoHSR();

        // Die TileSource beschreibt die Eigenschaften der Kacheln die wir anzeigen
        XYTileSource treasureMapTileSource = new XYTileSource("mbtiles", ResourceProxy.string.offline_mode, 1, 20, 256, ".png", new String[]{"http://example.org/"});

        File file = new File(Environment.getExternalStorageDirectory() /* entspricht /sdcard/ */, "hsr.mbtiles");

        /* Das verwenden von mbtiles ist leider ein wenig aufwändig, wir müssen
         * unsere XYTileSource in verschiedene Klassen 'verpacken' um sie dann
         * als TilesOverlay über der Grundkarte anzuzeigen.
         */
        MapTileModuleProviderBase treasureMapModuleProvider = new MapTileFileArchiveProvider(new SimpleRegisterReceiver(this),
                treasureMapTileSource, new IArchiveFile[]{MBTilesFileArchive.getDatabaseFileArchive(file)});

        MapTileProviderBase treasureMapProvider = new MapTileProviderArray(treasureMapTileSource, null,
                new MapTileModuleProviderBase[]{treasureMapModuleProvider});

        TilesOverlay treasureMapTilesOverlay = new TilesOverlay(treasureMapProvider, getBaseContext());
        treasureMapTilesOverlay.setLoadingBackgroundColor(Color.TRANSPARENT);

        // Jetzt können wir den Overlay zu unserer Karte hinzufügen:
        map.getOverlays().add(treasureMapTilesOverlay);

        controller.setZoom(21);
    }

    private void setUpGPS() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);

        // check if enabled and if not send user to the GSP settings
        // Better solution would be to display a dialog and suggesting to
        // go to the settings
        if (!enabled) {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }

        // Define the criteria how to select the locatioin provider -> use
        // default
        Criteria criteria = new Criteria();
        provider = locationManager.getBestProvider(criteria, false);
        Location location;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        } else {
            location = locationManager.getLastKnownLocation(provider);
        }

        // Initialize the location fields
        if (location != null) {
            System.out.println("Provider " + provider + " has been selected.");
            currentLocation = new GeoPoint(location);
            onLocationChanged(location);
        }

    }

    private void setUpMarkers() {
        Drawable marker=getResources().getDrawable(android.R.drawable.star_big_on);
        int markerWidth = marker.getIntrinsicWidth();
        int markerHeight = marker.getIntrinsicHeight();
        marker.setBounds(0, markerHeight, markerWidth, 0);

        ResourceProxy resourceProxy = new DefaultResourceProxyImpl(getApplicationContext());

        overlay = new MyItemizedOverlay(marker, resourceProxy);
        map.getOverlays().add(overlay);

        List<GeoPoint> markers = getMarkerPrefs();

        for(GeoPoint markerPoint: markers) {
            displayMarker(markerPoint);
        }
    }

    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }

    @Override
    public void onLocationChanged(Location location) {
        currentLocation = new GeoPoint(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }
}
