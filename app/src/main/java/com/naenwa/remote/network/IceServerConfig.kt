package com.naenwa.remote.network

import org.webrtc.PeerConnection

/**
 * ICE 서버 설정 - STUN/TURN 서버 구성
 * LTE 등 NAT 환경에서 P2P 연결을 위해 필요
 */
object IceServerConfig {

    // 공개 STUN 서버 목록
    private val publicStunServers = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun3.l.google.com:19302",
        "stun:stun4.l.google.com:19302",
        "stun:stun.cloudflare.com:3478",
        "stun:stun.nextcloud.com:443"
    )

    // 무료 공개 TURN 서버 (테스트용, 프로덕션에서는 자체 TURN 서버 권장)
    private val publicTurnServers = listOf(
        TurnServerInfo(
            url = "turn:openrelay.metered.ca:80",
            username = "openrelayproject",
            credential = "openrelayproject"
        ),
        TurnServerInfo(
            url = "turn:openrelay.metered.ca:443",
            username = "openrelayproject",
            credential = "openrelayproject"
        ),
        TurnServerInfo(
            url = "turn:openrelay.metered.ca:443?transport=tcp",
            username = "openrelayproject",
            credential = "openrelayproject"
        )
    )

    data class TurnServerInfo(
        val url: String,
        val username: String,
        val credential: String
    )

    /**
     * 기본 ICE 서버 목록 생성
     */
    fun getDefaultIceServers(): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()

        // STUN 서버 추가
        publicStunServers.forEach { stunUrl ->
            iceServers.add(
                PeerConnection.IceServer.builder(stunUrl)
                    .createIceServer()
            )
        }

        // TURN 서버 추가
        publicTurnServers.forEach { turn ->
            iceServers.add(
                PeerConnection.IceServer.builder(turn.url)
                    .setUsername(turn.username)
                    .setPassword(turn.credential)
                    .createIceServer()
            )
        }

        return iceServers
    }

    /**
     * 커스텀 TURN 서버 설정으로 ICE 서버 목록 생성
     */
    fun getIceServersWithCustomTurn(
        turnUrl: String,
        turnUsername: String,
        turnCredential: String
    ): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()

        // STUN 서버 추가
        publicStunServers.forEach { stunUrl ->
            iceServers.add(
                PeerConnection.IceServer.builder(stunUrl)
                    .createIceServer()
            )
        }

        // 커스텀 TURN 서버 추가
        iceServers.add(
            PeerConnection.IceServer.builder(turnUrl)
                .setUsername(turnUsername)
                .setPassword(turnCredential)
                .createIceServer()
        )

        return iceServers
    }

    /**
     * RTCConfiguration 생성
     */
    fun createRtcConfiguration(
        iceServers: List<PeerConnection.IceServer> = getDefaultIceServers()
    ): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
            // ICE 후보 수집 정책
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // Bundle 정책 (미디어 스트림 묶음)
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE

            // RTCP Mux 정책
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE

            // 연속 수집 정책 (후보를 계속 수집)
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

            // ICE 후보 풀 크기
            iceCandidatePoolSize = 2
        }
    }
}
