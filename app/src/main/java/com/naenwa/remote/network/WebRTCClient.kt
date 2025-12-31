package com.naenwa.remote.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.webrtc.*

/**
 * WebRTC 클라이언트 - STUN/TURN 서버를 사용한 P2P 연결
 * LTE 등 NAT 환경에서도 연결 가능
 */
class WebRTCClient(
    private val context: Context,
    private val signalingClient: SignalingClient
) {
    companion object {
        private const val TAG = "WebRTCClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebRTC 컴포넌트
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // EGL Context
    private var eglBase: EglBase? = null

    // 연결 상태
    private val _connectionState = MutableSharedFlow<RTCConnectionState>(replay = 1)
    val connectionState: SharedFlow<RTCConnectionState> = _connectionState

    // 데이터 채널 메시지
    private val _dataMessages = MutableSharedFlow<ByteArray>()
    val dataMessages: SharedFlow<ByteArray> = _dataMessages

    sealed class RTCConnectionState {
        object Disconnected : RTCConnectionState()
        object Connecting : RTCConnectionState()
        object Connected : RTCConnectionState()
        data class Error(val message: String) : RTCConnectionState()
    }

    init {
        initializePeerConnectionFactory()
        setupSignalingListener()
    }

    private fun initializePeerConnectionFactory() {
        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    private fun setupSignalingListener() {
        scope.launch {
            signalingClient.signalingMessages.collect { message ->
                when (message) {
                    is SignalingMessage.Offer -> handleOffer(message)
                    is SignalingMessage.Answer -> handleAnswer(message)
                    is SignalingMessage.IceCandidate -> handleRemoteIceCandidate(message)
                }
            }
        }
    }

    /**
     * PeerConnection 생성
     */
    fun createPeerConnection() {
        scope.launch {
            _connectionState.emit(RTCConnectionState.Connecting)
        }

        val rtcConfig = IceServerConfig.createRtcConfiguration()

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state: $state")
                    scope.launch {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                _connectionState.emit(RTCConnectionState.Connected)
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                _connectionState.emit(RTCConnectionState.Disconnected)
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                _connectionState.emit(RTCConnectionState.Error("ICE connection failed"))
                            }
                            else -> {}
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE receiving: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "Local ICE candidate: ${it.sdp}")
                        signalingClient.sendIceCandidate(
                            SignalingMessage.IceCandidate(
                                sdpMid = it.sdpMid,
                                sdpMLineIndex = it.sdpMLineIndex,
                                candidate = it.sdp
                            )
                        )
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d(TAG, "ICE candidates removed")
                }

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "Stream added")
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "Stream removed")
                }

                override fun onDataChannel(channel: DataChannel?) {
                    Log.d(TAG, "Data channel received")
                    channel?.let { setupDataChannel(it) }
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.d(TAG, "Track added")
                }
            }
        )

        Log.d(TAG, "PeerConnection created with STUN/TURN servers")
    }

    /**
     * 데이터 채널 생성 (Offer 생성 시)
     */
    fun createDataChannel(label: String = "data") {
        val config = DataChannel.Init().apply {
            ordered = true
        }
        dataChannel = peerConnection?.createDataChannel(label, config)
        dataChannel?.let { setupDataChannel(it) }
    }

    private fun setupDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {
                Log.d(TAG, "Data channel buffered amount: $amount")
            }

            override fun onStateChange() {
                Log.d(TAG, "Data channel state: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                scope.launch {
                    _dataMessages.emit(data)
                }
            }
        })
    }

    /**
     * Offer 생성 (연결 시작)
     */
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Offer created")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            signalingClient.sendOffer(
                                SignalingMessage.Offer(sdp = it.description)
                            )
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create failure: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set failure: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Offer creation failed: $error")
                scope.launch {
                    _connectionState.emit(RTCConnectionState.Error("Offer creation failed: $error"))
                }
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Answer 생성 (Offer 수신 후)
     */
    private fun createAnswer() {
        val constraints = MediaConstraints()

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Answer created")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            signalingClient.sendAnswer(
                                SignalingMessage.Answer(sdp = it.description)
                            )
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create failure: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set failure: $error")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Answer creation failed: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Offer 처리
     */
    private fun handleOffer(offer: SignalingMessage.Offer) {
        Log.d(TAG, "Handling offer")
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offer.sdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set (offer)")
                createAnswer()
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create failure: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set failure: $error")
            }
        }, sessionDescription)
    }

    /**
     * Answer 처리
     */
    private fun handleAnswer(answer: SignalingMessage.Answer) {
        Log.d(TAG, "Handling answer")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, answer.sdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set (answer)")
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create failure: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set failure: $error")
            }
        }, sessionDescription)
    }

    /**
     * 원격 ICE 후보 처리
     */
    private fun handleRemoteIceCandidate(candidate: SignalingMessage.IceCandidate) {
        Log.d(TAG, "Adding remote ICE candidate")
        val iceCandidate = IceCandidate(
            candidate.sdpMid,
            candidate.sdpMLineIndex,
            candidate.candidate
        )
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * 데이터 전송
     */
    fun sendData(data: ByteArray) {
        dataChannel?.let { channel ->
            if (channel.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap(data),
                    true
                )
                channel.send(buffer)
            } else {
                Log.w(TAG, "Data channel not open")
            }
        }
    }

    /**
     * 문자열 데이터 전송
     */
    fun sendText(text: String) {
        sendData(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 연결 종료
     */
    fun disconnect() {
        dataChannel?.close()
        dataChannel = null

        peerConnection?.close()
        peerConnection = null

        scope.launch {
            _connectionState.emit(RTCConnectionState.Disconnected)
        }
    }

    /**
     * 리소스 해제
     */
    fun release() {
        disconnect()

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null
    }
}
