package kr.co.nucode.ble.internal.model

import java.util.UUID
import kr.co.nucode.ble.CharacteristicRef

/** Callback route를 구분하는 GATT operation 종류. 공개 API로 노출하지 않는다. */
internal enum class GattOperationKind {
    Read,
    WriteWithResponse,
    WriteWithoutResponse,
    Subscribe,
    Unsubscribe,
    RequestMtu,
}

/**
 * 재탐색 뒤에도 다시 resolve할 수 있는 attribute identity.
 *
 * Android platform 객체 identity는 connection generation 안에서만 유효하므로 저장하지 않는다.
 */
internal sealed interface GattAttributeIdentity

internal data class GattCharacteristicIdentity(
    val serviceUuid: UUID,
    val serviceInstanceIndex: Int,
    val characteristicUuid: UUID,
    val characteristicInstanceIndex: Int,
) : GattAttributeIdentity {
    init {
        require(serviceInstanceIndex >= 0) { "serviceInstanceIndex는 0 이상이어야 한다: $serviceInstanceIndex" }
        require(characteristicInstanceIndex >= 0) {
            "characteristicInstanceIndex는 0 이상이어야 한다: $characteristicInstanceIndex"
        }
    }

    companion object {
        /**
         * `serviceInstanceIndex` 는 기본으로 ref 가 실어 나르는 [CharacteristicRef.service] 의
         * instance index 를 따른다 — 호출자가 다른 값을 넘기는 건 platform 재해석 경로뿐이다.
         */
        fun from(
            charRef: CharacteristicRef,
            serviceInstanceIndex: Int = charRef.service.instanceIndex,
        ): GattCharacteristicIdentity =
            GattCharacteristicIdentity(
                serviceUuid = charRef.service.uuid.value,
                serviceInstanceIndex = serviceInstanceIndex,
                characteristicUuid = charRef.uuid.value,
                characteristicInstanceIndex = charRef.instanceIndex,
            )
    }
}

internal data class GattDescriptorIdentity(
    val characteristic: GattCharacteristicIdentity,
    val descriptorUuid: UUID,
) : GattAttributeIdentity

/**
 * Session FIFO에 들어가는 typed operation. [generation]은 enqueue 당시 연결 세대다.
 * gatt 패키지의 엔진 private 큐 operation 클래스가 구현하므로 sealed가 아닌 일반 interface다(Kotlin sealed는 같은 패키지를 요구한다).
 */
internal interface GattOperation<out T> {
    val generation: Long
    val kind: GattOperationKind
    val attributeIdentity: GattAttributeIdentity?

    data class Read(
        override val generation: Long,
        override val attributeIdentity: GattCharacteristicIdentity,
    ) : GattOperation<ByteArray> {
        override val kind: GattOperationKind = GattOperationKind.Read
    }

    class WriteWithResponse(
        override val generation: Long,
        override val attributeIdentity: GattCharacteristicIdentity,
        chunk: ByteArray,
    ) : GattOperation<Unit> {
        override val kind: GattOperationKind = GattOperationKind.WriteWithResponse
        private val immutableChunk: ByteArray = chunk.copyOf()

        fun chunkCopy(): ByteArray = immutableChunk.copyOf()
    }

    class WriteWithoutResponse(
        override val generation: Long,
        override val attributeIdentity: GattCharacteristicIdentity,
        chunk: ByteArray,
    ) : GattOperation<Unit> {
        override val kind: GattOperationKind = GattOperationKind.WriteWithoutResponse
        private val immutableChunk: ByteArray = chunk.copyOf()

        fun chunkCopy(): ByteArray = immutableChunk.copyOf()
    }

    data class Subscribe(
        override val generation: Long,
        override val attributeIdentity: GattDescriptorIdentity,
    ) : GattOperation<Unit> {
        override val kind: GattOperationKind = GattOperationKind.Subscribe
    }

    data class Unsubscribe(
        override val generation: Long,
        override val attributeIdentity: GattDescriptorIdentity,
    ) : GattOperation<Unit> {
        override val kind: GattOperationKind = GattOperationKind.Unsubscribe
    }

    data class RequestMtu(
        override val generation: Long,
        val requested: Int,
    ) : GattOperation<GattMtuOutcome> {
        init {
            require(requested > 0) { "requested MTU는 양수여야 한다: $requested" }
        }

        override val kind: GattOperationKind = GattOperationKind.RequestMtu
        override val attributeIdentity: GattAttributeIdentity? = null
    }
}

/** MTU callback의 raw transport 결과. readiness 판정은 scheduler 밖 orchestration이 맡는다. */
internal data class GattMtuOutcome(val mtu: Int, val status: Int)
