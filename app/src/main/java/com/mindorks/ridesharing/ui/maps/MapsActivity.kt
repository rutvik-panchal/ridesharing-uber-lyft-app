package com.mindorks.ridesharing.ui.maps

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.AutocompleteActivity
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.mindorks.ridesharing.R
import com.mindorks.ridesharing.data.network.NetworkService
import com.mindorks.ridesharing.utils.*
import kotlinx.android.synthetic.main.activity_maps.*

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, MapsView {

    companion object {
        private const val TAG = "MapsActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 999
        private const val PICK_UP_REQUEST_CODE = 111
        private const val DROP_REQUEST_CODE = 112
    }

    private lateinit var presenter: MapsPresenter
    private lateinit var googleMap: GoogleMap
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private lateinit var locationCallBack: LocationCallback
    private var currentLatLng: LatLng? = null
    private var pickUpLatLng: LatLng? = null
    private var dropLatLng: LatLng? = null
    private var grayPolyline: Polyline? = null
    private var blackPolyline: Polyline? = null
    private val nearByCabMarkerList = arrayListOf<Marker>()
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var movingCabMarker: Marker? = null
    private var previousLatLngFromServer : LatLng? = null
    private var currentLatLngFromServer : LatLng? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)
        ViewUtils.enableTransparentStatusBar(window)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        presenter = MapsPresenter(NetworkService())
        presenter.onAttach(this)
        setUpClickListener()
    }

//    fun setDummyDropLocation() {
//        val testLatLng = LatLng(Constants.TEST_PICK_LAT, Constants.TEST_PICK_LNG)
//        dropLatLng = testLatLng
//    }

    private fun setUpClickListener() {
        pickUpTextView.setOnClickListener {
            launchLocationAutoCompleteActivity(PICK_UP_REQUEST_CODE)
        }
        dropTextView.setOnClickListener {
            launchLocationAutoCompleteActivity(DROP_REQUEST_CODE)
        }
        requestCabButton.setOnClickListener {
            statusTextView.visibility = View.VISIBLE
            statusTextView.text = getString(R.string.requesting_your_cab)
            requestCabButton.isEnabled = false
            pickUpTextView.isEnabled = false
            dropTextView.isEnabled = false
            Log.d(TAG, "Component isEnabled status \n RequestCabButton : ${requestCabButton.isEnabled}," +
                    " PickUpTextView : ${pickUpTextView.isEnabled}," +
                    " DropTextView : ${dropTextView.isEnabled}")

            presenter.requestCab(pickUpLatLng!!, dropLatLng!!)
        }
        nextRideButton.setOnClickListener {
            reset()
        }
    }

    private fun reset() {
        statusTextView.visibility = View.GONE
        nextRideButton.visibility = View.GONE
        nearByCabMarkerList.forEach {
            it.remove()
        }
        nearByCabMarkerList.clear()
        currentLatLngFromServer = null
        previousLatLngFromServer = null
        if (currentLatLng != null) {
            moveCamera(currentLatLng)
            animateCamera(currentLatLng)
            setCurrentLocationAsPickUp()
            presenter.requestNearbyCabs(currentLatLng!!)
        } else {
            pickUpTextView.text = ""
        }
        pickUpTextView.isEnabled = true
        dropTextView.isEnabled = true
        dropTextView.text = ""
        movingCabMarker?.remove()
        grayPolyline?.remove()
        blackPolyline?.remove()
        originMarker?.remove()
        destinationMarker?.remove()
        dropLatLng = null
        grayPolyline = null
        blackPolyline = null
        originMarker = null
        destinationMarker = null
        movingCabMarker = null
    }

    private fun checkAndShowRequestButton() {
        Log.d(TAG, "In checkAndShowRequestButton() : ${pickUpLatLng.toString()} and ${dropLatLng.toString()}")
        if (pickUpLatLng != null && dropLatLng != null) {
            requestCabButton.visibility = View.VISIBLE
            requestCabButton.isEnabled = true
        }
    }

    private fun launchLocationAutoCompleteActivity(requestCode: Int) {
        val fields: List<Place.Field> =
            arrayListOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)
        val intent =
            Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields).build(this)
        startActivityForResult(intent, requestCode)
    }

    private fun moveCamera(latLng: LatLng?) {
        googleMap.moveCamera(CameraUpdateFactory.newLatLng(latLng))
    }

    private fun animateCamera(latLng: LatLng?) {
        val cameraPosition = CameraPosition.Builder().target(latLng).zoom(15.5f).build()
        googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    private fun addCarMarkerAndGet(latLng: LatLng): Marker {
        val bitmapDescriptor = BitmapDescriptorFactory.fromBitmap(MapUtils.getCarBitmap(this))
        return googleMap.addMarker(MarkerOptions().position(latLng).flat(true).icon(bitmapDescriptor))
    }

    private fun addOriginDestinationMarkerAndGet(latLng: LatLng) : Marker {
        val bitmapDescriptor = BitmapDescriptorFactory.fromBitmap(MapUtils.getDestinationBitmap())
        return googleMap.addMarker(MarkerOptions().position(latLng).flat(true).icon(bitmapDescriptor))
    }

    private fun setCurrentLocationAsPickUp() {
        pickUpLatLng = currentLatLng
        pickUpTextView.text = getString(R.string.current_location)
        Log.d(TAG, "Current LAtLng set as pickup done set job done!")
    }

    private fun enableMyLocationMap() {
        googleMap.setPadding(0, ViewUtils.dpToPx(48f), 0, 0)
        googleMap.isMyLocationEnabled = true
    }

    private fun setUpLocationListener() {
        fusedLocationProviderClient = FusedLocationProviderClient(this)
        val locationRequest = LocationRequest().setInterval(2000).setFastestInterval(2000)
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)

        locationCallBack = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                if (currentLatLng == null) {
                    for (location in locationResult.locations) {
                        if (currentLatLng == null) {
                            Log.d(TAG, "Cureent Location = ${location.latitude}, ${location.longitude}")
                            currentLatLng = LatLng(location.latitude, location.longitude)
                            setCurrentLocationAsPickUp()

                            /////////////////
                            /////////////////
//                            setUpClickListener()
//                            setDummyDropLocation()
//                            checkAndShowRequestButton()
//                            if (requestCabButton.isEnabled) {
//                                Log.d(TAG, "Request Cab Button enabled")
//                            }
                            /////////////////
                            /////////////////

                            enableMyLocationMap()
                            moveCamera(currentLatLng)
                            animateCamera(currentLatLng)
                            presenter.requestNearbyCabs(currentLatLng!!)
                        }
                    }
                }
            }
        }

        fusedLocationProviderClient?.requestLocationUpdates(
            locationRequest,
            locationCallBack,
            Looper.myLooper()
        )
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.googleMap = googleMap
    }

    override fun onStart() {
        super.onStart()
        if (PermissionUtils.isAccessFineLocationGranted(this)) {
            if (PermissionUtils.isLocationEnable(this)) {
                // fetch the location
                setUpLocationListener()
            } else {
                PermissionUtils.showGPSNotEnabledDialog(this)
            }
        } else {
            PermissionUtils.requestAccessFineLocationPermission(this,
                LOCATION_PERMISSION_REQUEST_CODE)
        }

    }

    override fun onDestroy() {
        presenter.onDetach()
        fusedLocationProviderClient?.removeLocationUpdates(locationCallBack)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] ==
                        PackageManager.PERMISSION_GRANTED) {
                    if (PermissionUtils.isLocationEnable(this)) {
                        // fetch the location
                        setUpLocationListener()

                    } else {
                        PermissionUtils.showGPSNotEnabledDialog(this)
                    }
                } else {
                    Toast.makeText(this, getString(R.string.location_permission_not_granted),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun showNearbyCabs(latLngList: List<LatLng>) {
        nearByCabMarkerList.clear()
        for (latLng in latLngList) {
            val nearByCabMarker = addCarMarkerAndGet(latLng)
            nearByCabMarkerList.add(nearByCabMarker)
        }
    }

    override fun informCabBooked() {
        nearByCabMarkerList.forEach {
            it.remove()
        }
        nearByCabMarkerList.clear()
        requestCabButton.visibility = View.GONE
        statusTextView.text = getString(R.string.your_cab_is_booked)
    }

    override fun showPath(latLngList: List<LatLng>) {
        Log.d("MapsActivity", "in pickup path")

        val builder = LatLngBounds.builder()
        for (latLng in latLngList) {
            builder.include(latLng)
        }
        val bounds = builder.build()
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 2))

        val polylineOptions = PolylineOptions()
        polylineOptions.color(Color.GRAY)
        polylineOptions.width(5f)
        polylineOptions.addAll(latLngList)
        grayPolyline = googleMap.addPolyline(polylineOptions)

        val blackPolylineOptions = PolylineOptions()
        blackPolylineOptions.color(Color.BLACK)
        blackPolylineOptions.width(5f)
        blackPolylineOptions.addAll(latLngList)
        blackPolyline = googleMap.addPolyline(blackPolylineOptions)

        originMarker = addOriginDestinationMarkerAndGet(latLngList[0])
        originMarker?.setAnchor(0.5f, 0.5f)

        destinationMarker = addOriginDestinationMarkerAndGet(latLngList[latLngList.size - 1])
        destinationMarker?.setAnchor(0.5f, 0.5f)

        val polyLineAnimator = AnimationUtils.polyLineAnimator()
        polyLineAnimator.addUpdateListener { valueAnimator ->
            val percentValue = (valueAnimator.animatedValue as Int)
            val index = (grayPolyline?.points!!.size) * (percentValue / 100.0f).toInt()
            blackPolyline?.points = grayPolyline?.points!!.subList(0, index)
        }
        polyLineAnimator.start()
    }

    override fun updateCabLocation(latLng: LatLng) {
        if (movingCabMarker == null) {
            movingCabMarker = addCarMarkerAndGet(latLng)
        }
        if (previousLatLngFromServer == null) {
            currentLatLngFromServer = latLng
            previousLatLngFromServer = currentLatLngFromServer
            movingCabMarker?.position = currentLatLngFromServer
            movingCabMarker?.setAnchor(0.5f, 0.5f)
            animateCamera(latLng)
        } else {
            previousLatLngFromServer = currentLatLngFromServer
            currentLatLngFromServer = latLng
            val valueAnimator = AnimationUtils.cabAnimator()
            valueAnimator.addUpdateListener { va ->
                if (currentLatLngFromServer != null && previousLatLngFromServer != null) {
                    val multiplier = va.animatedFraction
                    val nextLocation = LatLng (
                        multiplier * currentLatLngFromServer!!.latitude
                                + (1 - multiplier) * previousLatLngFromServer!!.latitude,
                        multiplier * currentLatLngFromServer!!.longitude
                                + (1 - multiplier) * previousLatLngFromServer!!.longitude
                    )
                    movingCabMarker?.position = nextLocation
                    val rotation = MapUtils.getRotation(previousLatLngFromServer!!, nextLocation)
                    if (!rotation.isNaN()) {
                        movingCabMarker?.rotation = rotation
                    }
                    movingCabMarker?.setAnchor(0.5f, 0.5f)
                    animateCamera(nextLocation)
                }
            }
            valueAnimator.start()
        }
    }

    override fun informCabIsArriving() {
        statusTextView.text = getString(R.string.YOUR_CAB_IS_ARRIVING)
    }

    override fun informCabArrived() {
        statusTextView.text = getString(R.string.YOUR_CAB_HAS_ARRIVED)
        grayPolyline?.remove()
        blackPolyline?.remove()
        originMarker?.remove()
        destinationMarker?.remove()
    }

    override fun informTripStart() {
        statusTextView.text = getString(R.string.you_are_on_a_trip)
        previousLatLngFromServer = null
    }

    override fun informTripEnd() {
        statusTextView.text = getString(R.string.trip_end)
        nextRideButton.visibility = View.VISIBLE
        grayPolyline?.remove()
        blackPolyline?.remove()
        originMarker?.remove()
        destinationMarker?.remove()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_UP_REQUEST_CODE || requestCode == DROP_REQUEST_CODE) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    val place = Autocomplete.getPlaceFromIntent(data!!)
                    Log.d(TAG, "Place: " + place.name + ", " + place.id + ", " + place.latLng)
                    when (requestCode) {
                        PICK_UP_REQUEST_CODE -> {
                            pickUpTextView.text = place.name
                            pickUpLatLng = place.latLng
                            checkAndShowRequestButton()
                        }
                        DROP_REQUEST_CODE -> {
                            dropTextView.text = place.name
                            dropLatLng = place.latLng
                            checkAndShowRequestButton()
                        }
                    }
                }
                AutocompleteActivity.RESULT_ERROR -> {
                    val status: Status = Autocomplete.getStatusFromIntent(data!!)
                    Log.d(TAG, "Returned to Maps Activity" + status.statusMessage!!)
                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "Place Selection Canceled")
                }
            }
        }
    }

    override fun showRoutesNotAvailableError() {
        val error = getString(R.string.route_not_found_choose_different_locations)
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        reset()
    }

    override fun showDirectionApiFailedError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        reset()
    }
}