package io.github.leonidius20.recorder.data.recorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * this class is responsible for launching and stopping the RecorderService,
 * as well as communicating with it through the binder. The service is bound to
 * app context, so fragment/activity doesn't have to rebind on recreation.
 *
 * There is only one instance of this class, whereas there can be multiple instances
 * of the service class as the service is created and destroyed with each new recording.
 * Therefore, the timer and amplitude flows in this class cannot be just forwarded from
 * the service. They have to account for the service recreation.
 */
@Singleton
class RecorderServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServiceConnection {

    private var binder: RecorderService.Binder? = null

    enum class State {
        IDLE,
        RECORDING,
        PAUSED,
        ERROR,
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State>
        get() = _state


    private val _timer =  MutableStateFlow(0L)

    val timer: StateFlow<Long>
        get() = _timer

    private val _amplitudes = MutableSharedFlow<Int>(replay = 60)

    /**
     * emits max amplitude every 100ms. Used for audio visualization.
     * There is replay so that when the app goes into background and the fragment
     * displaying it unsubscribes, and then the app goes into foreground again and the
     * fragment resubscribes, the visualization reflects the newest samples.
     */
    val amplitudes = _amplitudes.asSharedFlow()


    /**
     * @return LiveData with the state of the RecorderService
     * that can be observed while the recording is in progress
     */
    fun launchRecording() {


        // if (!recordingsDirectory.exists()) mkdirs

        // todo: formatted time YYYY-MM-DD-HH-MM-SS-SSS


        ContextCompat.startForegroundService(
            context,
            Intent(context, RecorderService::class.java)
        )

        context.bindService(
            Intent(context, RecorderService::class.java),
            this,
            Context.BIND_IMPORTANT // todo: understand these values
        )
    }

    fun stopRecording() {
        context.stopService(
            Intent(context, RecorderService::class.java)
        )
        _state.value = State.IDLE
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun toggleRecPause() {
        binder!!.service.toggleRecPause()
    }

    override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?
    ) {
        binder = service as RecorderService.Binder

        binder!!.service.launcher = this

        // serviceScope is cancelled when the service is destroyed
        service.service.serviceScope.launch {
            service.service.state.onEach {
                when(it) {
                    RecorderService.State.RECORDING -> _state.value = State.RECORDING
                    RecorderService.State.PAUSED -> _state.value = State.PAUSED
                    RecorderService.State.ERROR -> {
                        // todo error ui state
                        _state.value = State.ERROR
                    }
                    RecorderService.State.PREPARING -> {
                        _state.value = State.IDLE
                    }
                }
            }.launchIn(this)

            service.service.timer.onEach {
                _timer.value = it
            }.launchIn(this)

            service.service.amplitudes.onEach {
                _amplitudes.emit(it)
            }.launchIn(this)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
    }

    /**
     * called by service itself when it is stopped
     */
    fun onServiceStopped() {
        _state.value = State.IDLE
    }

}