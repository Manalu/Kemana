package com.utsman.kemana.driver.fragment

import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.utsman.featurerabbitmq.Rabbit
import com.utsman.featurerabbitmq.Type
import com.utsman.kemana.base.*
import com.utsman.kemana.base.view.BottomSheetUnDrag
import com.utsman.kemana.driver.R
import com.utsman.kemana.driver.fragment.bottom_sheet.MainBottomSheet
import com.utsman.kemana.driver.impl.IMapView
import com.utsman.kemana.driver.impl.view_state.IActiveState
import com.utsman.kemana.driver.maps_callback.MainMaps
import com.utsman.kemana.driver.presenter.MapsPresenter
import com.utsman.kemana.driver.subscriber.*
import com.utsman.kemana.remote.driver.*
import com.utsman.kemana.remote.toJSONObject
import com.utsman.kemana.remote.toPassenger
import com.utsman.kemana.remote.toPlace
import com.utsman.smartmarker.mapbox.Marker
import io.reactivex.functions.Consumer
import isfaaghyth.app.notify.Notify
import isfaaghyth.app.notify.NotifyProvider
import kotlinx.android.synthetic.main.bottom_dialog_receiving_order.view.*
import kotlinx.android.synthetic.main.bottom_sheet.view.*
import kotlinx.android.synthetic.main.fragment_main.view.*
import org.json.JSONObject

@Suppress("UNCHECKED_CAST")
class MainFragment(private val driver: Driver?) : RxFragment(), IMapView, IActiveState {

    private lateinit var bottomSheet: BottomSheetUnDrag<View>
    private lateinit var mainBottomSheetFragment: MainBottomSheet

    private var onPassengerOrder = true

    private val bottomDialogView by lazy {
        LayoutInflater.from(context).inflate(R.layout.bottom_dialog_receiving_order, null)
    }

    private val bottomDialog by lazy {
        val bottomDialog = BottomSheetDialog(context!!)
        bottomDialog.setContentView(bottomDialogView)
        bottomDialog.setCancelable(false)

        return@lazy bottomDialog
    }

    private lateinit var mapView: MapView
    private lateinit var mapsPresenter: MapsPresenter
    private lateinit var mapbox: MapboxMap
    private lateinit var mainMaps: MainMaps

    private var marker: Marker? = null
    private var newLatLng = LatLng()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_main, container, false)
        mapView = view?.mapbox_view!!
        mapsPresenter = MapsPresenter(this)

        val subs = Notify.listen(LocationSubs::class.java, NotifyProvider(), Consumer { locationSubs ->
            logi("NOTIFY --> receiving location from service")
            mapsPresenter.initMap(locationSubs)
        })

        val updateSubs = Notify.listen(UpdateLocationSubs::class.java, NotifyProvider(), Consumer { updateLocationSubs ->
            logi("NOTIFY --> receiving location update from service")
            mapsPresenter.startUpdate(updateLocationSubs)
        })

        Handler().postDelayed({
            updateLocationActive()
        }, 2000)

        composite.addAll(subs, updateSubs)

        bottomSheet = BottomSheetBehavior.from(view.main_bottom_sheet) as BottomSheetUnDrag<View>
        bottomSheet.setAllowUserDragging(false)
        bottomSheet.hidden()

        mainBottomSheetFragment = MainBottomSheet(this)

        replaceFragment(mainBottomSheetFragment, R.id.main_frame_bottom_sheet)

        Notify.listenNotifyState { state ->
            when (state) {
                NotifyState.READY -> {
                    bottomSheet.collapse()
                }
            }
        }

        Notify.listen(ReadyOrderSubs::class.java, NotifyProvider(), Consumer { ready ->
            onPassengerOrder = ready.onOrder
        })

        Notify.listen(ObjectOrderSubs::class.java, NotifyProvider(), Consumer {
            val obj = it.jsonObject

            val passenger = obj.getJSONObject("person").toPassenger()
            val startPlace = obj.getJSONObject("startPlace").toPlace()
            val destPlace = obj.getJSONObject("destPlace").toPlace()
            val startName = obj.getString("start")
            val destName = obj.getString("destination")
            val distance = obj.getDouble("distance")

            val textPassengerName = bottomDialogView.text_name_passenger
            val textPricing = bottomDialogView.text_price
            val textDistance = bottomDialogView.text_distance
            val textFrom = bottomDialogView.text_from
            val textDest = bottomDialogView.text_to
            val btnAccept = bottomDialogView.btn_accept
            val btnReject = bottomDialogView.btn_reject

            textPassengerName.text = passenger.name
            textPricing.text = distance.calculatePricing()
            textDistance.text = distance.calculateDistanceKm()
            textFrom.text = startPlace.placeName
            textDest.text = destPlace.placeName

            btnAccept.setOnClickListener {
                callbackToPassenger(true, passenger)
            }

            btnReject.setOnClickListener {
                callbackToPassenger(false, passenger)
            }

            showBottomDialog()
        })

        return view
    }

    override fun onLocationReady(latLng: LatLng) {
        this.newLatLng = latLng

        mainMaps = MainMaps(context, latLng) { map, marker ->
            this.mapbox = map
            this.marker = marker

            /*compositeDisposable.timer(5000) {
                mapbox.animateCamera(CameraUpdateFactory.newLatLng(newLatLng))
            }*/
        }

        mapView.getMapAsync(mainMaps)
    }

    private fun updateLocationActive() {
        Notify.send(NotifyState(NotifyState.UPDATE_LOCATION))
    }

    override fun onLocationUpdate(newLatLng: LatLng) {
        this.newLatLng = newLatLng
        marker?.moveMarkerSmoothly(newLatLng)

        Notify.send(RotationSubs(marker?.getRotation()))
    }

    override fun onPickupPassenger() {

    }

    private fun callbackToPassenger(accepted: Boolean, passenger: Passenger) {
        val orderDataAttr = OrderDataAttr(
            orderID = System.currentTimeMillis().toString(),
            driver = driver,
            passenger = passenger
        )
        val orderData = OrderData(
            accepted = accepted,
            attribute = orderDataAttr
        )

        val jsonRequest = JSONObject()
        jsonRequest.apply {
            put("type", Type.ORDER_CONFIRM)
            put("data", orderData.toJSONObject())
        }

        if (onPassengerOrder) {
            Rabbit.fromUrl(RABBIT_URL)
                .publishTo(passenger.email!!, false, jsonRequest)
                .apply {
                    dismissBottomDialog()
                }
        } else {
            toast("order canceled from passenger")
            dismissBottomDialog()
        }
    }

    override fun activeState() {
        logi("state --> driver active")
        Notify.send(NotifyState(RemoteState.INSERT_DRIVER))
    }

    override fun deactivateState() {
        logi("state --> drive deactive")
        Notify.send(NotifyState(RemoteState.DELETE_DRIVER))
    }

    private fun dismissBottomDialog() {
        if (bottomDialog.isShowing) {
            bottomDialog.dismiss()
        }
    }

    private fun showBottomDialog() {
        if (!bottomDialog.isShowing) {
            bottomDialog.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        deactivateState()
    }

}