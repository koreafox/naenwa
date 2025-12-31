package com.naenwa.remote.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.*

/**
 * 시그널링 메시지 타입
 */
sealed class SignalingMessage {
    data class Offer(val sdp: String) : SignalingMessage()
    data class Answer(val sdp: String) : SignalingMessage()
    data class IceCandidate(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String
    ) : SignalingMessage()
}

/**
 * 시그널링 클라이언트 인터페이스
 */
interface SignalingClient {
    val signalingMessages: SharedFlow<SignalingMessage>

    fun sendOffer(offer: SignalingMessage.Offer)
    fun sendAnswer(answer: SignalingMessage.Answer)
    fun sendIceCandidate(candidate: SignalingMessage.IceCandidate)
}

/**
 * WebSocket 기반 시그널링 클라이언트
 * 기존 WebSocketClient를 확장하여 시그널링 메시지 처리
 */
class WebSocketSignalingClient(
    private val webSocket: WebSocket?,
    private val gson: Gson = Gson()
) : SignalingClient {

    companion object {
        private const val TAG = "SignalingClient"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _signalingMessages = MutableSharedFlow<SignalingMessage>()
    override val signalingMessages: SharedFlow<SignalingMessage> = _signalingMessages

    /**
     * 수신된 JSON 메시지 파싱
     */
    fun parseSignalingMessage(json: JsonObject) {
        val type = json.get("type")?.asString ?: return

        scope.launch {
            when (type) {
                "webrtc_offer" -> {
                    val sdp = json.get("sdp")?.asString ?: return@launch
                    Log.d(TAG, "Received offer")
                    _signalingMessages.emit(SignalingMessage.Offer(sdp))
                }

                "webrtc_answer" -> {
                    val sdp = json.get("sdp")?.asString ?: return@launch
                    Log.d(TAG, "Received answer")
                    _signalingMessages.emit(SignalingMessage.Answer(sdp))
                }

                "webrtc_ice_candidate" -> {
                    val sdpMid = json.get("sdpMid")?.asString ?: return@launch
                    val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: return@launch
                    val candidate = json.get("candidate")?.asString ?: return@launch
                    Log.d(TAG, "Received ICE candidate")
                    _signalingMessages.emit(
                        SignalingMessage.IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    )
                }
            }
        }
    }

    override fun sendOffer(offer: SignalingMessage.Offer) {
        val data = JsonObject().apply {
            addProperty("action", "webrtc_offer")
            addProperty("sdp", offer.sdp)
        }
        send(data)
    }

    override fun sendAnswer(answer: SignalingMessage.Answer) {
        val data = JsonObject().apply {
            addProperty("action", "webrtc_answer")
            addProperty("sdp", answer.sdp)
        }
        send(data)
    }

    override fun sendIceCandidate(candidate: SignalingMessage.IceCandidate) {
        val data = JsonObject().apply {
            addProperty("action", "webrtc_ice_candidate")
            addProperty("sdpMid", candidate.sdpMid)
            addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.candidate)
        }
        send(data)
    }

    private fun send(data: JsonObject) {
        val json = gson.toJson(data)
        Log.d(TAG, "Sending signaling: $json")
        webSocket?.send(json)
    }
}
